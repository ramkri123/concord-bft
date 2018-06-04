/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { async, ComponentFixture, TestBed } from '@angular/core/testing';

import { MockSharedModule } from '../../shared/shared.module';
import { RouterTestingModule } from '@angular/router/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';

import { CreateContractModalComponent } from '../create-contract-modal/create-contract-modal.component';
import { SmartContractsContainerComponent } from './smart-contracts-container.component';

describe('SmartContractsContainerComponent', () => {
  let component: SmartContractsContainerComponent;
  let fixture: ComponentFixture<SmartContractsContainerComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      imports: [
        RouterTestingModule,
        HttpClientTestingModule,
        MockSharedModule
      ],
      declarations: [ SmartContractsContainerComponent, CreateContractModalComponent ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SmartContractsContainerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
