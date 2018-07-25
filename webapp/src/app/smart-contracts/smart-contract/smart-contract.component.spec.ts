/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';

import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MockSharedModule } from '../../shared/shared.module';
import { RouterTestingModule } from '@angular/router/testing';

import { SmartContractComponent } from './smart-contract.component';
import { SmartContractVersionComponent } from '../smart-contract-version/smart-contract-version.component';
import {
  SmartContractsSolidityFunctionInputsComponent
} from '../smart-contracts-solidity-function-inputs/smart-contracts-solidity-function-inputs.component';
import { ContractPayloadPreviewFormComponent } from '../contract-payload-preview-form/contract-payload-preview-form.component';
import { SmartContractsService } from "../shared/smart-contracts.service";
import { of } from "rxjs";
import { ActivatedRoute } from "@angular/router";

class MockActivatedRoute extends ActivatedRoute {
  constructor() {
    super();
    this.params = of({ contractId: "2", version: "1" });
    // let paramsObservable = this.params.asObservable();
  }
}

describe('SmartContractComponent', () => {
  let component: SmartContractComponent;
  let fixture: ComponentFixture<SmartContractComponent>;
  let service: SmartContractsService;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      imports: [
        MockSharedModule,
        RouterTestingModule,
        FormsModule,
        HttpClientTestingModule
      ],
      declarations: [
        SmartContractComponent,
        SmartContractVersionComponent,
        ContractPayloadPreviewFormComponent,
        SmartContractsSolidityFunctionInputsComponent
      ],
      providers: [
        SmartContractsService,
        { provide: ActivatedRoute, useClass: MockActivatedRoute }
      ]
    })
      .compileComponents();

    service = TestBed.get(SmartContractsService);

  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SmartContractComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load smart contract with given contractId', () => {
    const spy = spyOn(service, 'getSmartContract').and.returnValue(of({ smartContract: 'smart contract' }));
    component.loadSmartContract('contractId');
    expect(spy).toHaveBeenCalled();
  });

  it('should load version details for given contractId and version', () => {
    const spy = spyOn(service, 'getVersionDetails').and.returnValue(of({ versionDetails: 'version details' }));
    component.loadVersionDetails('contractId', 'version');
    expect(spy).toHaveBeenCalled();
  });

  it('should navigate to the version details page', () => {
    const spy = spyOn((component as any).router, 'navigate');
    component.smartContract = {
      contract_id: '1',
      owner: 'owner',
      versions: undefined
    };

    component.versionSelected = 1;
    component.getVersionInfo();
    expect(spy).toHaveBeenCalledWith(['smart-contracts', '1', 'versions', 1]);
  });

  describe('should load smart contract with contractId in the params', () => {

    it('should load smartContract with contractId in the params', () => {
      const smartContractSpy = spyOn(component, 'loadSmartContract');
      component.smartContract = {
        contract_id: '1',
        owner: 'owner',
        versions: undefined
      };

      component.loadSmartContract('2');
      expect(smartContractSpy).toHaveBeenCalled();
    });
  });

});
