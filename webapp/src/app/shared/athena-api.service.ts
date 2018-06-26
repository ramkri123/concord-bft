/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { of as observableOf, forkJoin as observableForkJoin } from 'rxjs';
import { mergeMap } from 'rxjs/operators';

import { ATHENA_API_PREFIX } from './shared.config';

import {
  Member,
  Block,
  BlockListing,
  Transaction,
  SmartContract,
  SmartContractVersion
} from './remote-interfaces';

@Injectable()
export class AthenaApiService {

  constructor(@Inject(ATHENA_API_PREFIX) private athenaApiPrefix: string, private httpClient: HttpClient) {}

  getMembers() {
    return this.httpClient.get<Member[]>(this.apiPath('/members'));
  }

  getBlocks(count: number = 10) {
    const params = new HttpParams().set('count', count.toString());

    return this.httpClient.get<BlockListing>(this.apiPath('/blocks'), {params: params});
  }

  getBlocksByUrl(url: string) {
    return this.httpClient.get<BlockListing>(url);
  }

  getBlock(blockNumber) {
    return this.httpClient.get<Block>(this.apiPath(`/blocks/${blockNumber}`));
  }

  getTransaction(transactionHash: string) {
    return this.httpClient.get<Transaction>(this.apiPath(`/transactions/${transactionHash}`));
  }

  getRecentTransactions() {
    // Get blocks, then get individual block, then build list of recent transactions from the data returned
    // This is temporary until there is an endpoint to fetch recent transactions

    return this.getBlocks(1000).pipe(mergeMap(resp => {
      const blockObservables = resp.blocks.map((block) => this.getBlock(block.number));

      return observableForkJoin(blockObservables).pipe(mergeMap(blocksResp => {
        let blockTransactions: any[] = [];

        blocksResp.forEach((block) => {
          const tempTransactions: any = (block as Block).transactions;
          tempTransactions.map(x => x.blockNumber = (block as Block).number);
          blockTransactions = blockTransactions.concat(tempTransactions);
        });

        const transactionObservables = blockTransactions.map((blockTransaction) => this.getTransaction(blockTransaction.hash));

        return observableForkJoin(transactionObservables).pipe(mergeMap(transationsResp => {
          const transactions = transationsResp.map((transaction, index) => {
            return { blockNumber: blockTransactions[index].blockNumber, ...transaction };
          });

          return observableOf(transactions);
        }));
      }));
    }));
  }

  getSmartContracts() {
    return this.httpClient.get<SmartContract[]>(this.apiPath('/contracts'));
  }

  getSmartContract(contractId: string) {
    return this.httpClient.get<SmartContract>(this.apiPath(`/contracts/${contractId}`));
  }

  getVersionDetails(contractId: string, version: string) {
    return this.httpClient.get<SmartContractVersion>(this.apiPath(`/contracts/${contractId}/versions/${version}`));
  }

  postContract(contract) {
    return this.httpClient.post<any>(this.apiPath('/contracts'), contract);
  }

  apiPath(path: string) {
    return `${this.athenaApiPrefix}${path}`;
  }
}
