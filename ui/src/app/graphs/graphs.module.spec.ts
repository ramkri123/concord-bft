/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { GraphsModule } from './graphs.module';

describe('GraphsModule', () => {
  let graphsModule: GraphsModule;

  beforeEach(() => {
    graphsModule = new GraphsModule();
  });

  it('should create an instance', () => {
    expect(graphsModule).toBeTruthy();
  });
});
