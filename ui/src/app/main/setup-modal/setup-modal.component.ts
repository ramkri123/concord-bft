/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { Component } from '@angular/core';
import { Router } from '@angular/router';

import { BlockchainService, BlockchainResponse } from '../../shared/blockchain.service';

@Component({
  selector: 'concord-setup-modal',
  templateUrl: './setup-modal.component.html',
  styleUrls: ['./setup-modal.component.scss']
})
export class SetupModalComponent {
  blockchain: BlockchainResponse;
  isOpen = false;

  constructor(
    private router: Router,
    private blockchainService: BlockchainService
  ) {}

  deploy() {
    this.close();
    this.router.navigate(['deploy'], {fragment: 'deploy'});
  }

  go() {
    this.blockchain = this.blockchainService.selectedBlockchain;
    this.close();
    this.router.navigate([this.blockchain.id, 'dashboard'], {fragment: 'orgTour'});
  }

  open() {
    this.isOpen = true;
  }

  close() {
    this.isOpen = false;
  }

}