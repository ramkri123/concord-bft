/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Component, Input, OnInit } from '@angular/core';

import { Transaction } from '../../shared/remote-interfaces';
import { AthenaApiService } from '../../shared/athena-api.service';

@Component({
  selector: 'app-transaction-details',
  templateUrl: './transaction-details.component.html',
  styleUrls: ['./transaction-details.component.scss']
})
export class TransactionDetailsComponent implements OnInit {
  @Input() transactionHash: string;

  transaction: Transaction;

  constructor(private athenaApiService: AthenaApiService) { }

  ngOnInit() {
    this.loadTransaction(this.transactionHash);
  }

  loadTransaction(transactionHash: string) {
    this.athenaApiService.getTransaction(transactionHash).subscribe((response) => {
      this.transaction = response;
    });
  }
}
