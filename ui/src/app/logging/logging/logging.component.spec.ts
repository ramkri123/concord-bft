/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { MockSharedModule } from '../../shared/shared.module';
import { LoggingComponent } from './logging.component';
import { GraphsModule } from '../../graphs/graphs.module';
import { LogDetailsComponent } from '../log-details/log-details.component';
import { ExportChartDataModalComponent } from '../export-chart-data-modal/export-chart-data-modal.component';
import { ExportLogEventsModalComponent } from '../export-log-events-modal/export-log-events-modal.component';
import { ErrorAlertService } from '../../shared/global-error-handler.service';

describe('LoggingComponent', () => {
  let component: LoggingComponent;
  let fixture: ComponentFixture<LoggingComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      imports: [ MockSharedModule, GraphsModule, HttpClientTestingModule, NoopAnimationsModule ],
      declarations: [
        LoggingComponent,
        LogDetailsComponent,
        ExportChartDataModalComponent,
        ExportLogEventsModalComponent
      ],
      providers: [ ErrorAlertService ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(LoggingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});