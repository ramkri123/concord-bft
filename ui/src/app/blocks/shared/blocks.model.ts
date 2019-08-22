/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

/**
 * GET response of fetching a list of blocks
 */
export interface BlockListing {
  blocks: BlockListingBlock[];
  next: string;
}

/**
 * A simplified block provided when fetching a list of blocks
 */
export interface BlockListingBlock {
  number: number;
  hash: string;
  url: string;
}

/**
 * GET response of fetching an individual block
 */
export interface Block {
  hash: string;
  nonce: string;
  parentHash: string;
  number: number;
  size: number;
  transactions: [{hash: string; url: string; }];
}