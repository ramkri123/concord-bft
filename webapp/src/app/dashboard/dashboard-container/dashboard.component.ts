/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Component, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { BlockListingBlock } from '../../blocks/shared/blocks.model';
import { TransactionsService } from '../../transactions/shared/transactions.service';
import { BlockchainWizardComponent } from '../../shared/components/blockchain-wizard/blockchain-wizard.component';
import { TaskManagerService } from '../../shared/task-manager.service';

import * as NodeGeoJson from '../features.json';

@Component({
  selector: 'athena-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss']
})
export class DashboardComponent implements OnInit {
  @ViewChild('setupWizard') setupWizard: BlockchainWizardComponent;
  blocks: BlockListingBlock[];
  recentTransactions: any[] = [];
  nodeGeoJson: any = NodeGeoJson;
  mockStats = {
    totalActiveNodes: 28458,
    inactiveNodes: 583,
    overallNodeHealth: .8742123,
    transactionsPerSecond: 4289,
    averageValidationTime: 1.98
  };

  constructor(
    private transactionsService: TransactionsService,
    private taskManager: TaskManagerService,
    private route: ActivatedRoute
  ) { }

  ngOnInit() {
    this.transactionsService.getRecentTransactions().subscribe((resp) => {
      this.recentTransactions = resp;
    });

    this.route.fragment.subscribe(fragment => {
      switch (fragment) {
        case 'deploy':
          this.setupBlockchain();
          break;

        default:
          // code...
          break;
      }
    });
  }

  setupBlockchain() {
    this.setupWizard.open();
  }

  onSetupComplete(blockchainInfo) {
    this.taskManager.handleBlockchainSetup(blockchainInfo);
  }
}
