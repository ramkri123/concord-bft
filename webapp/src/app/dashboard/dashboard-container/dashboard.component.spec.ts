/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { HttpClientTestingModule } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { MockSharedModule } from '../../shared/shared.module';

import { TransactionsStatusFilterComponent } from '../../shared/components/transactions-status-filter/transactions-status-filter.component';
import { TransactionListComponent } from '../../transactions/transaction-list/transaction-list.component';
import { TransactionDetailsComponent } from '../../transactions/transaction-details/transaction-details.component';
import { DashboardComponent } from './dashboard.component';
import { WorldMapComponent } from '../world-map/world-map.component';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: ComponentFixture<DashboardComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      imports: [
        RouterTestingModule,
        HttpClientTestingModule,
        MockSharedModule
      ],
      declarations: [
        DashboardComponent,
        WorldMapComponent,
        TransactionsStatusFilterComponent,
        TransactionListComponent,
        TransactionDetailsComponent
      ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
