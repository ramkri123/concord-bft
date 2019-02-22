/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

// This file is required by karma.conf.js and loads recursively all the .spec and framework files

import 'zone.js/dist/zone-testing';
import { getTestBed } from '@angular/core/testing';
import {
  BrowserDynamicTestingModule,
  platformBrowserDynamicTesting
} from '@angular/platform-browser-dynamic/testing';

const TIMEOUT_INTERVAL = 30000; // 30 seconds
declare const require: any;

// First, initialize the Angular testing environment.
getTestBed().initTestEnvironment(
  BrowserDynamicTestingModule,
  platformBrowserDynamicTesting()
);
// Extend the global jasmine timeout interval
jasmine.DEFAULT_TIMEOUT_INTERVAL = TIMEOUT_INTERVAL;
// Then we find all the tests.
const context = require.context('./', true, /\.spec\.ts$/);
// And load the modules.
context.keys().map(context);
