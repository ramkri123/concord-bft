/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Component, Input, OnInit } from '@angular/core';

@Component({
  selector: 'athena-block-graph',
  templateUrl: './block-graph.component.html',
  styleUrls: ['./block-graph.component.scss']
})
export class BlockGraphComponent implements OnInit {
  @Input('graphData') graphData: any[];

  // graph options
  showXAxis = true;
  showYAxis = true;
  gradient = true;
  schemeType = 'ordinal';
  showGridLines = true;
  yScaleMax = 100;
  colorScheme = {
    domain: ['#0094d2']
  };


  constructor() {
  }

  ngOnInit() {
  }

}

