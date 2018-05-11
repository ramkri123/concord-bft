/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Injectable, NgModule, Pipe, PipeTransform } from '@angular/core';

import { TranslateService } from '@ngx-translate/core';

@Pipe({name: 'translate'})
export class MockTranslatePipe implements PipeTransform {
  transform(value: string): string {
    return value;
  }
}

@Injectable()
export class MockTranslateService  {
  getBrowserLang() {}
  setDefaultLang() {}
  use() {}
}

@NgModule({
  providers: [{provide: TranslateService, useClass: MockTranslateService}],
  declarations: [MockTranslatePipe],
  exports: [MockTranslatePipe]
})
export class MockTranslateModule {}

