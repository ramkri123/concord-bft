/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { browser, by, element } from 'protractor';

export class AppPage {
  logOut() {
    browser.waitForAngularEnabled(false);
    element(by.css('#profile-menu')).click();
    browser.waitForAngularEnabled(true);
    browser.waitForAngularEnabled(false);
    element(by.css('#log-out-button')).click();
    browser.waitForAngularEnabled(true);
  }

  getTourTitle() {
    return element(by.css('.ngxp-title'));
  }

  getTourNextButton() {
    return element(by.css('.ngxp-btn.btn-next'));
  }

  clickTourEndButton() {
    element(by.css('.ngxp-btn.btn-end')).click();
  }
}
