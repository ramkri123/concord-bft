/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { Inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

import { CONCORD_API_PREFIX } from '../../shared/shared.config';
import { SmartContract, SmartContractVersion } from './smart-contracts.model';
import { ConcordApiService } from '../../shared/concord-api';
import { BlockchainService } from '../../shared/blockchain.service';

@Injectable({
  providedIn: 'root'
})
export class SmartContractsService extends ConcordApiService {
  contractToolsApi: string = 'api/concord/contracts/';
  constructor(
    @Inject(CONCORD_API_PREFIX) concordApiPrefix: string,
    private httpClient: HttpClient,
    // @ts-ignore: no unused locals
    private blockchainService: BlockchainService
  ) {
    super(concordApiPrefix);
  }

  get apiSubPath() {
    return 'contracts';
  }

  getCompilerVersion() {
    return this.httpClient.get<any>(`${this.contractToolsApi}compiler_versions`);
  }

  getSmartContracts() {
    return this.httpClient.get<SmartContract[]>(this.resourcePath());
  }

  getSmartContract(contractId: string) {
    return this.httpClient.get<SmartContract>(this.resourcePath(contractId));
  }

  getVersionDetails(contractId: string, version: string) {
    return this.httpClient.get<SmartContractVersion>(this.resourcePath(`${contractId}/versions/${version}`));
  }

  updateExistingVersion(contractId: string, request) {
    return this.httpClient.put<SmartContractVersion>(this.resourcePath(`${contractId}`), request);
  }

  postContract(contract) {
    return this.httpClient.post<any>(this.resourcePath(), contract);
  }

  postSourceCode(request) {
    return this.httpClient.post<any>(`${this.contractToolsApi}compile`, request);
  }

}