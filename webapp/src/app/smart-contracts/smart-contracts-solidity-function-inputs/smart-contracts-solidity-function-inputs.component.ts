/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';

import { AbiFunctionParameter } from '../shared/smart-contracts.model';
import { isBytes, isHexAddress, isInt, isUint } from '../shared/custom-validators';

@Component({
  selector: 'athena-smart-contracts-solidity-function-inputs',
  templateUrl: './smart-contracts-solidity-function-inputs.component.html',
  styleUrls: ['./smart-contracts-solidity-function-inputs.component.scss']
})
export class SmartContractsSolidityFunctionInputsComponent implements OnInit, OnChanges {

  @Input() formGroup: FormGroup;
  @Input() functionInputs: AbiFunctionParameter[];

  constructor() {
  }

  ngOnInit() {
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes.functionInputs) {
      for (const functionInput of Object.keys(this.functionInputs)) {
        this.formGroup.addControl(this.functionInputs[functionInput].name, this.getFormControl(this.functionInputs[functionInput].type));
      }
    }
  }

  getFormControl(controlType: string): FormControl {
    const intRegex = /^int.*$/;
    const uintRegex = /^uint.*$/;
    const bytesRegex = /^byte[s]?\d{0,2}$/;
    if (intRegex.test(controlType)) {
      return new FormControl('', [Validators.required, isInt(controlType)]);
    } else if (uintRegex.test(controlType)) {
      return new FormControl('', [Validators.required, isUint(controlType)]);
    } else if (controlType === 'address') {
      return new FormControl('', [Validators.required, isHexAddress]);
    } else if (bytesRegex.test(controlType)) {
      return new FormControl('', [Validators.required, isBytes(controlType)]);
    } else {
      return new FormControl('', [Validators.required]);
    }
  }
}


