/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { InjectionToken } from '@angular/core';

export const ATHENA_API_PREFIX = new InjectionToken<string>('AthenaApiPrefix');
export const ETHEREUM_API_PREFIX = new InjectionToken<string>('EthereumApiPrefix');
export const ADDRESS_LENGTH = 42;
export const ADDRESS_PATTERN = new RegExp(/(0x|0X)[a-fA-F0-9]+$/);
