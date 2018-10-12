/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { TestBed, inject } from '@angular/core/testing';
import { HttpClient, HttpHandler } from '@angular/common/http';

import { SmartContractsService } from './smart-contracts.service';
import { ATHENA_API_PREFIX } from '../../shared/shared.config';

describe('SmartContractsService', () => {
  let service: SmartContractsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SmartContractsService,
        { provide: ATHENA_API_PREFIX, useValue: 'api/athena' },
        HttpClient,
        HttpHandler
      ]
    });

    service = TestBed.get(SmartContractsService);
  });

  it('should be created', inject([SmartContractsService], (contractService: SmartContractsService) => {
    expect(contractService).toBeTruthy();
  }));

  it('should fetch all smart contracts', () => {
      const httpSpy = spyOn((service as any).httpClient, 'get');
      service.getSmartContracts();
      expect(httpSpy).toHaveBeenCalledWith('api/athena/contracts');
    }
  );

  it('should fetch smart contract with given id', () => {
      const httpSpy = spyOn((service as any).httpClient, 'get');
      service.getSmartContract('contractId');
      expect(httpSpy).toHaveBeenCalledWith('api/athena/contracts/contractId');
  });

  it('should fetch version details for given versionId and contractId', () => {
    const httpSpy = spyOn((service as any).httpClient, 'get');
    service.getVersionDetails('contractId', 'version');
    expect(httpSpy).toHaveBeenCalledWith('api/athena/contracts/contractId/versions/version');
  });

  it('should post a smart contract with given payload', () => {
    const httpSpy = spyOn((service as any).httpClient, 'post');
    const contract = {
      id: 1,
      from: '0x262C0D7AB5FFD4EDE2199F6EA793F819E1ABB019',
      contract_id: '1',
      version: '1',
      sourcecode: 'sourceCode'
    };

    service.postContract(contract);
    expect(httpSpy).toHaveBeenCalledWith('api/athena/contracts', contract);
  });

});



