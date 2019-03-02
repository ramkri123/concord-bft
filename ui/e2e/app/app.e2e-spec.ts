/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { AuthHelper } from '../helpers/auth';
import { LoginPage } from '../login/login.po';
import { MarketingPage } from '../marketing/marketing.po';
import { DashboardPage } from '../dashboard/dashboard.po';

describe('concord-ui App', () => {
  let authHelper: AuthHelper;
  let loginPage: LoginPage;
  let dashboardPage: DashboardPage;
  let marketingPage: MarketingPage;

  beforeEach(() => {
    loginPage = new LoginPage();
    dashboardPage = new DashboardPage();
    marketingPage = new MarketingPage();
  });

  afterAll(() => {
    authHelper = new AuthHelper();
    authHelper.logOut();
  });

  // TODO This will be revisited at a later date, when we add
  // the marketing page back to the flow
  // it('should display the page title', () => {
  //   marketingPage.navigateTo();
  //   marketingPage.clickLoginButton();
  //   loginPage.fillLogInForm('admin@blockchain.local', 'T3sting!');
  //   expect(dashboardPage.getPageTitle()).toEqual('VMware concord');
  // });
});