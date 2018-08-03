/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { browser, by, element } from 'protractor';

export class SignUpPage {
  navigateTo() {
    return browser.get('/auth/signup');
  }

  getSignUpForm() {
    return element(by.css('#signup-form'));
  }

  fillSignUpForm(firstName, lastName, email, company, jobTitle, country, relationship, numEmployees) {
    element(by.css('#firstName')).sendKeys(firstName);
    element(by.css('#lastName')).sendKeys(lastName);
    element(by.css('#email')).sendKeys(email);
    element(by.css('#company')).sendKeys(company);
    element(by.css('#jobTitle')).sendKeys(jobTitle);
    element(by.css('#country')).sendKeys(country);
    element(by.css('#relationship')).sendKeys(relationship);
    element(by.css('#numberOfEmployees')).sendKeys(numEmployees);
    element(by.css('#signup-form button[type="submit"]')).click();
  }
}
