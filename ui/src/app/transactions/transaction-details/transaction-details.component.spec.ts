/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';

import { VmwCopyToClipboardButtonComponent } from '../../shared/components/copy-to-clipboard-button/copy-to-clipboard-button.component';
import { MockSharedModule } from '../../shared/shared.module';
import { TransactionDetailsComponent } from './transaction-details.component';

describe('TransactionDetailsComponent', () => {
  let component: TransactionDetailsComponent;
  let fixture: ComponentFixture<TransactionDetailsComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      imports: [
        HttpClientTestingModule,
        MockSharedModule
      ],
      declarations: [
        TransactionDetailsComponent,
        VmwCopyToClipboardButtonComponent,
      ]
    })
      .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TransactionDetailsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});