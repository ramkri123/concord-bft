/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { BlockchainService } from './blockchain.service';

describe('BlockchainService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule]
    });
  });

  it('should be created', () => {
    const service: BlockchainService = TestBed.get(BlockchainService);
    expect(service).toBeTruthy();
  });
});