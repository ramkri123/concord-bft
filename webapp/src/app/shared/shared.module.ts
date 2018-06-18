/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { NgModule, ModuleWithProviders } from '@angular/core';

import { CommonModule } from '@angular/common';
import { ReactiveFormsModule } from '@angular/forms';
import { ClarityModule } from '@clr/angular';
import { TranslateModule } from '@ngx-translate/core';
import { MockTranslateModule } from '../mocks/mock-translate.module';

import { AuthenticationService } from './authentication.service';
import { AuthenticatedGuard } from './authenticated-guard.service';
import { AthenaApiService } from './athena-api.service';
import { EthApiService } from './eth-api.service';
import { ATHENA_API_PREFIX, ETHEREUM_API_PREFIX } from './shared.config';
import { TransactionsStatusFilterComponent } from './components/transactions-status-filter/transactions-status-filter.component';
import {TransactionListViewComponent} from "./components/transaction-list-view/transaction-list-view.component";
import {RouterModule} from "@angular/router";

@NgModule({
  imports: [
    CommonModule,
    TranslateModule,
    ClarityModule,
    ReactiveFormsModule,
    RouterModule
  ],
  declarations: [TransactionsStatusFilterComponent, TransactionListViewComponent],
  exports: [
    CommonModule,
    TranslateModule,
    ClarityModule,
    TransactionsStatusFilterComponent,
    ReactiveFormsModule,
    TransactionListViewComponent
  ]
})
export class SharedModule {
  public static forRoot(): ModuleWithProviders {
    return {
      ngModule: SharedModule,
      providers: [
        AuthenticationService,
        AuthenticatedGuard,
        {provide: ATHENA_API_PREFIX, useValue: '/api/athena'},
        {provide: ETHEREUM_API_PREFIX, useValue: '/api/athena/eth'},
        AthenaApiService,
        EthApiService
      ]
    };
  }
}

@NgModule({
  imports: [
    CommonModule,
    MockTranslateModule,
    ClarityModule,
    ReactiveFormsModule
  ],
  providers: [
    AuthenticationService,
    AuthenticatedGuard,
    {provide: ATHENA_API_PREFIX, useValue: '/api/athena'},
    {provide: ETHEREUM_API_PREFIX, useValue: '/api/athena/eth'},
    AthenaApiService,
    EthApiService
  ],
  exports: [
    CommonModule,
    MockTranslateModule,
    ClarityModule,
    ReactiveFormsModule
  ]
})
export class MockSharedModule {}

