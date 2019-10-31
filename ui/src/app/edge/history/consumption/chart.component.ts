import { formatNumber } from '@angular/common';
import { Component, Input, OnChanges, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { DefaultTypes } from 'src/app/shared/service/defaulttypes';
import { ChannelAddress, Edge, EdgeConfig, Service, Utils } from '../../../shared/shared';
import { ChartOptions, Data, DEFAULT_TIME_CHART_OPTIONS, TooltipItem } from './../shared';
import { AbstractHistoryChart } from '../abstracthistorychart';
import { QueryHistoricTimeseriesDataResponse } from 'src/app/shared/jsonrpc/response/queryHistoricTimeseriesDataResponse';

@Component({
    selector: 'consumptionChart',
    templateUrl: '../abstracthistorychart.html'
})
export class ConsumptionChartComponent extends AbstractHistoryChart implements OnInit, OnChanges {

    @Input() private period: DefaultTypes.HistoryPeriod;

    public evcsComponents: EdgeConfig.Component[] = null;

    ngOnChanges() {
        this.updateChart();
    };

    constructor(
        protected service: Service,
        private route: ActivatedRoute,
        private translate: TranslateService
    ) {
        super(service);
    }


    ngOnInit() {
        this.service.setCurrentComponent('', this.route);
        this.setLabel();
    }

    protected updateChart() {
        this.loading = true;
        this.queryHistoricTimeseriesData(this.period.from, this.period.to).then(response => {
            this.service.getCurrentEdge().then(() => {
                this.service.getConfig().then((config) => {
                    this.evcsComponents = config.getComponentsImplementingNature("io.openems.edge.evcs.api.Evcs").filter(component => !(component.factoryId == 'Evcs.Cluster'));
                    let result = (response as QueryHistoricTimeseriesDataResponse).result;
                    console.log("RESULT", result)
                    // convert labels
                    let labels: Date[] = [];
                    for (let timestamp of result.timestamps) {
                        labels.push(new Date(timestamp));
                    }
                    this.labels = labels;

                    // convert datasets
                    let datasets = [];

                    if ('_sum/ConsumptionActivePower' in result.data) {
                        /*
                        * Consumption
                         */
                        let consumptionData = result.data['_sum/ConsumptionActivePower'].map(value => {
                            if (value == null) {
                                return null
                            } else {
                                return value / 1000; // convert to kW
                            }
                        });
                        datasets.push({
                            label: this.translate.instant('General.Consumption'),
                            data: consumptionData,
                            hidden: false
                        });
                        this.colors.push({
                            backgroundColor: 'rgba(253,197,7,0.05)',
                            borderColor: 'rgba(253,197,7,1)',
                        })
                    }
                    if ('_sum/ConsumptionActivePowerL1' && '_sum/ConsumptionActivePowerL2' && '_sum/ConsumptionActivePowerL3' in result.data) {
                        let consumptionDataL1 = result.data['_sum/ConsumptionActivePowerL1'].map(value => {
                            if (value == null) {
                                return null
                            } else {
                                return value / 1000; // convert to kW
                            }
                        });
                        let consumptionDataL2 = result.data['_sum/ConsumptionActivePowerL2'].map(value => {
                            if (value == null) {
                                return null
                            } else {
                                return value / 1000; // convert to kW
                            }
                        });
                        let consumptionDataL3 = result.data['_sum/ConsumptionActivePowerL3'].map(value => {
                            if (value == null) {
                                return null
                            } else {
                                return value / 1000; // convert to kW
                            }
                        });
                        datasets.push({
                            label: this.translate.instant('General.Consumption') + ' ' + this.translate.instant('General.Phase') + ' ' + 'L1',
                            data: consumptionDataL1
                        });
                        this.colors.push({
                            backgroundColor: 'rgba(255,165,0,0.1)',
                            borderColor: 'rgba(255,165,0,1)',
                        });
                        datasets.push({
                            label: this.translate.instant('General.Consumption') + ' ' + this.translate.instant('General.Phase') + ' ' + 'L2',
                            data: consumptionDataL2
                        });
                        this.colors.push({
                            backgroundColor: 'rgba(255,165,0,0.1)',
                            borderColor: 'rgba(255,165,0,1)',
                        });
                        datasets.push({
                            label: this.translate.instant('General.Consumption') + ' ' + this.translate.instant('General.Phase') + ' ' + 'L3',
                            data: consumptionDataL3
                        });
                        this.colors.push({
                            backgroundColor: 'rgba(255,165,0,0.1)',
                            borderColor: 'rgba(255,165,0,1)',
                        });
                    }
                    this.datasets = datasets;
                    this.loading = false;
                }).catch(reason => {
                    console.error(reason); // TODO error message
                    this.initializeChart();
                    return;
                });
            }).catch(reason => {
                console.error(reason); // TODO error message
                this.initializeChart();
                return;
            });
        }).catch(reason => {
            console.error(reason); // TODO error message
            this.initializeChart();
            return;
        });
    }

    protected getChannelAddresses(edge: Edge, config: EdgeConfig): Promise<ChannelAddress[]> {
        return new Promise((resolve) => {
            let result: ChannelAddress[] = [
                new ChannelAddress('_sum', 'ConsumptionActivePower'),
                new ChannelAddress('_sum', 'ConsumptionActivePowerL1'),
                new ChannelAddress('_sum', 'ConsumptionActivePowerL2'),
                new ChannelAddress('_sum', 'ConsumptionActivePowerL3'),
            ];
            // TODO ENTSCHEIDEN OB EVCS DATEN IN CONSUMPTION ODER EIGENEM WIDGET SIND
            // config.getComponentsImplementingNature("io.openems.edge.evcs.api.Evcs").filter(component => !(component.factoryId == 'Evcs.Cluster')).forEach(component => {
            //     result.push(new ChannelAddress(component.id, 'ChargePower'));
            // })
            resolve(result);
        })
    }

    protected setLabel() {
        let options = <ChartOptions>Utils.deepCopy(DEFAULT_TIME_CHART_OPTIONS);
        options.scales.yAxes[0].scaleLabel.labelString = "kW";
        options.tooltips.callbacks.label = function (tooltipItem: TooltipItem, data: Data) {
            let label = data.datasets[tooltipItem.datasetIndex].label;
            let value = tooltipItem.yLabel;
            if (label == this.grid) {
                if (value < 0) {
                    value *= -1;
                    label = this.gridBuy;
                } else {
                    label = this.gridSell;
                }
            }
            return label + ": " + formatNumber(value, 'de', '1.0-2') + " kW";
        }
        this.options = options;
    }

    public getChartHeight(): number {
        return window.innerHeight / 2.5;
    }
}