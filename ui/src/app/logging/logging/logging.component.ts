/*
 * Copyright 2019 VMware, all rights reserved.
 */

import { Component, OnInit, ViewChild } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { LogApiService } from '../shared/log-api.service';
import { LogTaskCompletedResponse, LogListEntry, LogCountEntry } from '../shared/logging.model';
import { ExportLogEventsModalComponent } from '../export-log-events-modal/export-log-events-modal.component';
import { ExportChartDataModalComponent } from '../export-chart-data-modal/export-chart-data-modal.component';

// Time constants in milliseconds
const ONE_SECOND = 1000;
const THIRTY_SECONDS = ONE_SECOND * 30;
const ONE_MINUTE = ONE_SECOND * 60;
const FIVE_MINUTES = ONE_MINUTE * 5;
const TEN_MINUTES = ONE_MINUTE * 10;
const FIFTEEN_MINUTES = ONE_MINUTE * 15;
const ONE_HOUR = ONE_MINUTE * 60;
const SIX_HOURS = ONE_HOUR * 6;
const TWELVE_HOURS = ONE_HOUR * 12;
const ONE_DAY = ONE_HOUR * 24;
const SEVEN_DAYS = ONE_DAY * 7;
const THIRTY_DAYS = ONE_DAY * 30;

@Component({
  selector: 'concord-logging',
  templateUrl: './logging.component.html',
  styleUrls: ['./logging.component.scss']
})
export class LoggingComponent implements OnInit {
  @ViewChild('exportLogEventsModal') exportLogEventsModal: ExportLogEventsModalComponent;
  @ViewChild('exportChartDataModal') exportChartDataModal: ExportChartDataModalComponent;
  logs: LogListEntry[] = [];
  logCounts: LogCountEntry[] = [];
  totalCount: number = null;
  listLoading: boolean = true;
  countLoading: boolean = true;
  startTime: number = null;
  endTime: number = null;
  graphData: { name: string, series: { name: Date, value: number }[] }[];
  heatMapData: { name: string, series: { name: string, value: number }[] }[];
  nextPageLink: string = null;
  documentSelfLink: string = null;
  timePeriods: {title: string, value: number, interval: number, xAxisLabel?: string, yAxisLabel?: string}[] = [
    {
      title: this.translate.instant('logging.timePeriods.oneMinute'),
      value: ONE_MINUTE,
      interval: ONE_SECOND
    },
    {
      title: this.translate.instant('logging.timePeriods.fiveMinutes'),
      value: FIVE_MINUTES,
      interval: ONE_SECOND
    },
    {
      title: this.translate.instant('logging.timePeriods.fifteenMinutes'),
      value: FIFTEEN_MINUTES,
      interval: THIRTY_SECONDS
    },
    {
      title: this.translate.instant('logging.timePeriods.oneHour'),
      value: ONE_HOUR,
      interval: ONE_MINUTE
    },
    {
      title: this.translate.instant('logging.timePeriods.sixHours'),
      value: SIX_HOURS,
      interval: ONE_MINUTE,
      xAxisLabel: this.translate.instant('logging.heatMap.axisLabels.hour'),
      yAxisLabel: this.translate.instant('logging.heatMap.axisLabels.minute')
    },
    {
      title: this.translate.instant('logging.timePeriods.twelveHours'),
      value: TWELVE_HOURS,
      interval: ONE_MINUTE,
      xAxisLabel: this.translate.instant('logging.heatMap.axisLabels.hour'),
      yAxisLabel: this.translate.instant('logging.heatMap.axisLabels.minute')
    },
    {
      title: this.translate.instant('logging.timePeriods.oneDay'),
      value: ONE_DAY,
      interval: TEN_MINUTES,
      xAxisLabel: this.translate.instant('logging.heatMap.axisLabels.hour'),
      yAxisLabel: this.translate.instant('logging.heatMap.axisLabels.minute')
    },
    {
      title: this.translate.instant('logging.timePeriods.sevenDays'),
      value: SEVEN_DAYS,
      interval: ONE_HOUR,
      xAxisLabel: this.translate.instant('logging.heatMap.axisLabels.day'),
      yAxisLabel: this.translate.instant('logging.heatMap.axisLabels.hour')
    },
    {
      title: this.translate.instant('logging.timePeriods.thirtyDays'),
      value: THIRTY_DAYS,
      interval: ONE_HOUR,
      xAxisLabel: this.translate.instant('logging.heatMap.axisLabels.day'),
      yAxisLabel: this.translate.instant('logging.heatMap.axisLabels.hour')
    },
  ];
  selectedTimePeriod: {title: string, value: number, interval: number} = null;

  constructor(private logApiService: LogApiService, private translate: TranslateService) {
    this.xAxisTickFormatting = this.xAxisTickFormatting.bind(this);
  }

  ngOnInit() {
    this.onSelectTimePeriod(this.timePeriods[7]);
  }

  fetchLogs() {
    this.listLoading = true;
    this.logApiService.postToTasks(this.startTime, this.endTime).subscribe((resp) => {
      this.documentSelfLink = resp.documentSelfLink;
      this.pollLogStatus(resp.documentSelfLink, 'logs', this.onFetchLogsComplete.bind(this));
    });
  }

  fetchLogCounts() {
    this.countLoading = true;
    this.heatMapData = [];
    this.logApiService.postToTasksCount(this.startTime, this.endTime, this.selectedTimePeriod.interval).subscribe((resp) => {
      this.pollLogStatus(resp.documentSelfLink, 'logCounts', (logResp) => {
        this.logCounts = logResp.logQueryResults;
        this.totalCount = logResp.totalRecordCount;
        this.parseCounts();
        this.countLoading = false;
      });
    });
  }

  onClickExportLogEvents() {
    this.exportLogEventsModal.open();
  }

  onClickExportChartData() {
    this.exportChartDataModal.open();
  }

  onFetchNextPage() {
    this.listLoading = true;
    this.logApiService.fetchLogStatus(this.nextPageLink).subscribe((resp) => {
      this.pollLogStatus(resp.documentSelfLink, 'logs', this.onFetchLogsComplete.bind(this));
    });
  }

  onSelectTimePeriod(timePeriod) {
    if (this.selectedTimePeriod !== null && timePeriod.value === this.selectedTimePeriod.value) {
      return;
    }

    this.selectedTimePeriod = timePeriod;
    // fetch logs again with new parameters
    this.endTime = new Date().getTime();
    this.startTime = this.endTime - this.selectedTimePeriod.value;
    this.logs = [];
    this.fetchLogs();
    this.fetchLogCounts();
  }

  xAxisTickFormatting(date) {
    const hours = date.getUTCHours();
    const minutes = date.getUTCMinutes();
    const seconds = date.getUTCSeconds();
    const twelveHourTime = hours > 12 ? hours - 12 : hours;
    const zeroPaddedMinutes = minutes < 10 ? `0${minutes}` : minutes;
    const zeroPaddedSeconds = seconds < 10 ? `0${seconds}` : seconds;
    const amPm = hours > 12 ? 'pm' : 'am';
    if (this.selectedTimePeriod.value < FIFTEEN_MINUTES) {
      return `${twelveHourTime}:${zeroPaddedMinutes}:${zeroPaddedSeconds} ${amPm}`;
    } else if (this.selectedTimePeriod.value <= TWELVE_HOURS) {
      return `${twelveHourTime}:${zeroPaddedMinutes} ${amPm}`;
    } else if (this.selectedTimePeriod.value === ONE_DAY) {
      return `${twelveHourTime} ${amPm}`;
    } else {
      return `${this.translate.instant(`logging.heatMap.days.${date.getUTCDay()}`)} ${date.getUTCMonth() + 1}/${date.getUTCDate()}`;
    }
  }

  get isSmallSeries() {
    return this.selectedTimePeriod.value < SIX_HOURS;
  }

  get heatMapTitle() {
    return this.selectedTimePeriod.value > TWELVE_HOURS ?
      this.translate.instant('logging.heatMap.dailyTitle') :
      this.translate.instant('logging.heatMap.hourlyTitle');
  }

  private getHeatMapLabelMinutes(minutes) {
    // set minutes from parameter and return :mm
    if (minutes === '0') {
      return `:0${minutes}`;
    } else {
      return `:${minutes}`;
    }
  }

  private getHeatMapXLabelDays(day) {
    return this.translate.instant(`logging.heatMap.days.${day.toString()}`);
  }

  private onFetchLogsComplete(logResp: LogTaskCompletedResponse) {
    this.logs = this.logs.concat(logResp.logQueryResults);
    this.nextPageLink = logResp.nextPageLink;
    this.listLoading = false;
  }

  private pollLogStatus(link: string, type: string, callback: (n: LogTaskCompletedResponse) => void) {
    this.logApiService.fetchLogStatus(link).subscribe((logResponse) => {
      if (logResponse.taskInfo.stage === 'FINISHED') {
        callback(logResponse);
      } else {
        setTimeout(() => {
          this.pollLogStatus(link, type, callback);
        }, 1000);
      }
    });
  }

  private parseCounts() {
    if (this.selectedTimePeriod.value >= SEVEN_DAYS) {
      this.parseWeeklyAndMonthlyCounts();
    } else if (this.selectedTimePeriod.value >= SIX_HOURS && this.selectedTimePeriod.value <= TWELVE_HOURS) {
      this.parseSixAndTwelveHourCounts();
    } else if (this.selectedTimePeriod.value === ONE_DAY) {
      this.parseDailyCounts();
    }

    this.parseAreaChartCounts();
  }

  private parseAreaChartCounts() {
    const parsedCounts = [];
    this.logCounts.map((count) => {
      const date = new Date(count.timestamp);
      const value = parseInt(count['count(*)'], 10);
      // Add the name and value object to parsedCounts as is required for area chart data formatting
      parsedCounts.push({ 'name': date, 'value': value });
    });
    this.graphData = [{
      'name': this.translate.instant('logging.logTable.title'),
      'series': parsedCounts
    }];
  }

  private parseDailyCounts() {
    const heatMapObj = {};
    this.logCounts.map((count) => {
      const date = new Date(count.timestamp);
      const hour = (Math.floor(date.getUTCHours() / 2) * 2);
      const minutes = Math.floor(date.getUTCMinutes() / 10) * 10;
      const value = parseInt(count['count(*)'], 10);

      heatMapObj[hour] = heatMapObj[hour] || {};

      heatMapObj[hour][minutes] = heatMapObj[hour][minutes] + value || value;
    });

    this.heatMapData = this.populateHeatMapData(heatMapObj, (hour) => hour.toString(), this.getHeatMapLabelMinutes);
  }

  private parseSixAndTwelveHourCounts() {
    const heatMapObj = {};
    this.logCounts.map((count) => {
      const date = new Date(count.timestamp);
      const hour = date.getUTCHours().toString();
      const minutes = Math.floor(date.getUTCMinutes() / 10) * 10;
      const value = parseInt(count['count(*)'], 10);

      heatMapObj[hour] = heatMapObj[hour] || {};

      heatMapObj[hour][minutes] = heatMapObj[hour][minutes] + value || value;
    });

    this.heatMapData = this.populateHeatMapData(heatMapObj, (hour) => hour.toString(), this.getHeatMapLabelMinutes);
  }

  private parseWeeklyAndMonthlyCounts() {
    // An interstitial object to hold the day/hour counts before converting it to the required formatting for a heat map
    // https://swimlane.gitbook.io/ngx-charts/examples/heat-map-chart#data-format
    const heatMapObj = {
      0: {},
      1: {},
      2: {},
      3: {},
      4: {},
      5: {},
      6: {},
    };
    this.logCounts.map((count) => {
      const date = new Date(count.timestamp);
      // Parse the day and hour from the timestamp
      const day = date.getUTCDay();
      const hour = date.getUTCHours();
      const value = parseInt(count['count(*)'], 10);
      // Add the value for the given hour to the existing hour key or create it if necessary
      // This should scale as long as the interval for log counts is an hour or less
      heatMapObj[day][hour] = heatMapObj[day][hour] + value || value;
    });

    // Move sunday to the end of the array
    const heatMapData = this.populateHeatMapData(
      heatMapObj,
      this.getHeatMapXLabelDays.bind(this),
      (hour) => hour.toString()
    );
    const sundayData = heatMapData.shift();
    heatMapData.push(sundayData);
    this.heatMapData = heatMapData;
  }

  private populateHeatMapData(heatMapObj, xLabelFn, yLabelFn) {
    const heatMapData = [];

    Object.keys(heatMapObj).forEach((xValue) => {
      const yAxisArray = [];
      // Loop over each y axis data point
      Object.keys(heatMapObj[xValue]).forEach((yValue) => {
        // Add the name and value object as required by heat map chart data formatting
        yAxisArray.push({ name: yLabelFn(yValue), value: heatMapObj[xValue][yValue] });
      });
      // add the y axis series data to the x axis value.
      heatMapData.push({ name: xLabelFn(xValue), series: yAxisArray.reverse() });
    });

    return heatMapData;
  }
}
