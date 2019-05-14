/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { browser, protractor, ProtractorExpectedConditions, element } from 'protractor';

import { SmartContractsPage } from './smart-contracts.po';
import { SmartContractPage } from './smart-contract.po';
import { AuthHelper } from '../helpers/auth';
import { LoginPage } from '../login/login.po';
import { BROWSER_WAIT_TIME } from '../helpers/constants';
import { waitFor, waitForText } from '../helpers/utils';

declare var require: any;

const path = require('path');

describe('concord-ui Smart Contracts', () => {
  let authHelper: AuthHelper;
  let loginPage: LoginPage;
  let smartContractsPage: SmartContractsPage;
  let smartContractPage: SmartContractPage;
  let from: string;
  let contractId: string;
  let version: string;
  let file: string;
  let until: ProtractorExpectedConditions;
  let compilerVersion: string;

  beforeAll(() => {
    until = protractor.ExpectedConditions;
    loginPage = new LoginPage();
    authHelper = new AuthHelper();
    loginPage.navigateTo();
    browser.sleep(1000);
    loginPage.fillLogInForm('admin@blockchain.local', 'T3sting!');
  });

  afterAll(() => {
    authHelper.logOut();
  });

  beforeEach(() => {
    smartContractsPage = new SmartContractsPage();
    smartContractPage = new SmartContractPage();
    from = '0x5BB088F57365907B1840E45984CAE028A82AF934';
    contractId = 'contractId';
    version = 'version1';
    compilerVersion = '0.5.4';
    file = '../files/somefile.sol';
    browser.waitForAngularEnabled(false);
    waitFor('#smartContracts');
    smartContractsPage.navigateTo();
  });

  it('should create a smart contract', () => {
    const absolutePath = path.resolve(__dirname, file);
    smartContractsPage.openCreateModal();
    browser.wait(until.presenceOf(smartContractPage.getPageTitle()), BROWSER_WAIT_TIME);
    smartContractsPage.fillContractFormStep1(from, contractId, version, compilerVersion, absolutePath);
    smartContractsPage.clickWizardNextButton();
    waitForText(element(by.cssContainingText('.modal-title', 'Contract Selection')));
    smartContractsPage.clickWizardNextButton();
    waitForText(element(by.cssContainingText('.modal-title', 'Constructor Parameters')));
    smartContractsPage.addProprosals();
    smartContractsPage.clickWizardFinishButton();
    waitFor('.contract-form');
    expect(smartContractPage.getContractId()).toBe(contractId);
  });

  it('should navigate to the smart contract page with the latest version selected', () => {
    const expectedLinkText = `${contractId}`;
    smartContractsPage.getTableLinkElement(expectedLinkText).click();
    waitFor('.contract-form');
    expect(smartContractPage.getContractId()).toBe(contractId);
    expect(smartContractPage.getVersionName()).toBe(version);
    expect(smartContractPage.getFunctionsForm().isPresent()).toBe(true);
  });

  it('should send a call when call is clicked with a valid form', () => {
    smartContractPage.navigateTo();
    waitFor('.contract-form');
    expect(smartContractPage.getCallSuccessAlert().isPresent()).toBe(false);
    smartContractPage.fillParameterForm(from, 'call');
    waitFor('.call-success');
    expect(smartContractPage.getCallSuccessAlert().isPresent()).toBe(true);
  });

  it('should send a transaction when transaction is clicked with a valid form', () => {
    smartContractPage.navigateTo();
    waitFor('.contract-form');
    expect(smartContractPage.getTransactionSuccessAlert().isPresent()).toBe(false);
    smartContractPage.fillParameterForm(from, 'transaction');
    waitFor('.send-success');
    expect(smartContractPage.getTransactionSuccessAlert().isPresent()).toBe(true);
  });
});