/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { HttpResponse, HttpErrorResponse } from '@angular/common/http';
import { TranslateService } from '@ngx-translate/core';

import { BlockchainService } from '../../shared/blockchain.service';
import { DeployStates } from '../../shared/blockchain.model';

@Component({
  selector: 'concord-deploying-interstitial',
  templateUrl: './deploying-interstitial.component.html',
  styleUrls: ['./deploying-interstitial.component.scss']
})
export class DeployingInterstialComponent {
  loading: boolean = true;
  title: string = 'Deploying';
  message: string ;
  error: string;
  showInterstitial = false;

  constructor(
    private blockchainService: BlockchainService,
    private router: Router,
    private translateService: TranslateService
  ) {
    this.blockchainService.notify.subscribe(message => {
      if (message && message.message === 'deploying') {
        this.loading = true;
        this.showInterstitial = true;
      }
    });
  }

  startLoading(response: HttpResponse<any> | HttpErrorResponse) {
    this.error = null;
    this.loading = true;
    this.showInterstitial = true;

    if (response && response['task_id']) {
      this.pollUntilDeployFinished(response['task_id']);
    } else {
      this.showError(response);
    }
  }

  private pollUntilDeployFinished(taskId: string) {
    this.blockchainService.pollDeploy(taskId)
      .subscribe((response) => {

        if (response.state === DeployStates.SUCCEEDED) {
          this.blockchainService.set().subscribe(() => {
            // TODO: enable the dashboard to show a toast - response.resource_id is the bid
            this.router.navigate([`/${response.resource_id}`, 'dashboard'], {fragment: 'orgTour'});
            this.showInterstitial = false;
            this.blockchainService.notify.next({message: 'deployed'});
          });
        } else {
          this.showError(response);
        }
      }, error => {
         this.showError(error);
      });
  }

  private showError(error: HttpResponse<any> | HttpErrorResponse) {
    this.title = this.translateService.instant('error.title');
    this.error = error['message'] || error['error_message'];
    this.loading = false;
  }
}