package io.openems.edge.evcs.cluster;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.common.channel.AccessMode;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.calculate.CalculateAverage;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.ManagedEvcs;

import org.osgi.service.metatype.annotations.Designate;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Evcs.Cluster", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS, //
				EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
		})
public class EvcsCluster extends AbstractOpenemsComponent implements OpenemsComponent, EventHandler, Evcs {

	private int totalcurrentPowerLimit;
	private Double maximalUsedHardwarePower;
	private final List<Evcs> evcss = new CopyOnWriteArrayList<>();

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MULTIPLE)
	protected void addEvcs(Evcs evcs) {
		// Do not add myself
		if (evcs == this) {
			return;
		}

		this.evcss.add(evcs);
	}

	protected void removeEvcs(Evcs evcs) {
		if (evcs == this) {
			return;
		}

		this.evcss.remove(evcs);
	}

	@Reference
	protected ConfigurationAdmin cm;

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// Maximum current valid by the Hardware.
		HARDWARE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("The maximum current in Ampere that kann be used by the cable. ")),

		// Maximum current valid by the user.
		MANUAL_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) //
				.accessMode(AccessMode.READ_WRITE) //
				.text("The maximum current in Ampere that will be used for all charging Stations."));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public EvcsCluster() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				Evcs.ChannelId.values(), //
				ChannelId.values() //
		);
	}

	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled());

		// Depending on the user inputs, the minimum of the limits will be used;
		int currentCurrLimit = config.manualCurrentLimit() == 0 ? config.hardwareCurrentLimit()
				: Math.min(config.hardwareCurrentLimit(), config.manualCurrentLimit());
		this.totalcurrentPowerLimit = currentCurrLimit * 230 * 3;

		// TODO: React on grid limit too (Minimum of cable and grid) => but where do we
		// have to set it

		this.getMaximumHardwarePower().setNextValue(config.hardwareCurrentLimit());
		this.channel(ChannelId.MANUAL_CURRENT_LIMIT).setNextValue(config.manualCurrentLimit());

		// update filter for 'evcss' component
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "evcss", config.evcs_ids())) {
			return;
		}

	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		switch (event.getTopic()) {

		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.calculateChannelValues();
			break;

		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			// If the maximum Power that can be reached by all Cars can't reach the limit
			if (this.maximalUsedHardwarePower == null || this.maximalUsedHardwarePower < totalcurrentPowerLimit) {
				break;
			}
			this.limitEvcss();
			break;
		}

	}

	/**
	 * Calculates the sum of all EVCS charging powers and set it as channel values
	 * of this clustered EVCS
	 */
	private void calculateChannelValues() {
		final CalculateAverage chargePower = new CalculateAverage();
		final CalculateAverage minHardwarePower = new CalculateAverage();
		final CalculateAverage maxHardwarePowerOfAll = new CalculateAverage();

		for (Evcs evcs : evcss) {
			chargePower.addValue(evcs.getChargePower());
			minHardwarePower.addValue(evcs.getMinimumHardwarePower());
			maxHardwarePowerOfAll.addValue(evcs.getMaximumHardwarePower());
		}
		this.getChargePower().setNextValue(chargePower.calculate());
		this.getMinimumHardwarePower().setNextValue(minHardwarePower.calculate());
		this.maximalUsedHardwarePower = maxHardwarePowerOfAll.calculate();

	}

	private void limitEvcss() {

		// Depending on the user inputs, the minimum of the limits will be used;

		// Total Power that is left to reach the hardware, manual or maximumPower limit
		double totalPowerLeft = totalcurrentPowerLimit;

		// If a maximum power is present, e.g. from another cluster, then the limit will
		// be the that value or lower
		if (this.getMaximumPower().value().asOptional().isPresent()) {
			if (totalPowerLeft > this.getMaximumPower().value().get()) {
				totalPowerLeft = this.getMaximumPower().value().get();
			}
		}

		for (Evcs evcs : evcss) {
			
			if (evcs instanceof ManagedEvcs) {
				int chargePowerValue;
				Optional<Integer> requestedPower = ((ManagedEvcs) evcs).setChargePowerRequest().getNextWriteValueAndReset();

				if (requestedPower.isPresent()) {
					chargePowerValue = requestedPower.get();
				} else {
					chargePowerValue = evcs.getChargePower().value().orElse(0);
					evcs.getMaximumPower().setNextValue(evcs.getMaximumHardwarePower().value().orElse(22080));
				}
				
				if (chargePowerValue < totalPowerLeft) {
					((ManagedEvcs) evcs).setChargePower().setNextValue(chargePowerValue);
					totalPowerLeft = totalPowerLeft - chargePowerValue;
					System.out.println("Power : " + totalPowerLeft + " ; ChargePower: " + chargePowerValue);
				} else {
					((ManagedEvcs) evcs).setChargePower().setNextValue(totalPowerLeft);
					evcs.getMaximumPower().setNextValue(totalPowerLeft);
					totalPowerLeft = 0;
				}
			} else {
				int evcsHardwarePower = evcs.getMaximumHardwarePower().value().orElse(0);
				if (evcsHardwarePower < totalPowerLeft) {
					evcs.getMaximumPower().setNextValue(evcsHardwarePower);
					totalPowerLeft = totalPowerLeft - evcsHardwarePower;
				} else {
					evcs.getMaximumPower().setNextValue(totalPowerLeft);
					totalPowerLeft = 0;
				}
			}

		}

		/*
		 * for (Evcs evcs : evcss) { int evcsHardwarePower =
		 * evcs.getMaximumHardwarePower().value().orElse(0); if(evcsHardwarePower <
		 * totalPowerLeft) { evcs.getMaximumPower().setNextValue(evcsHardwarePower);
		 * totalPowerLeft -= evcsHardwarePower; }else {
		 * evcs.getMaximumPower().setNextValue(totalPowerLeft); totalPowerLeft = 0; } }
		 * 
		 */
		/*
		 * 
		 * 
		 * int evcsNeededPower = 0;
		 * 
		 * Optional<Integer> maxPower = evcs.getMaximumPower().value().asOptional();
		 * if(maxPower.isPresent()) { evcsNeededPower = evcs. }
		 */
	}
}