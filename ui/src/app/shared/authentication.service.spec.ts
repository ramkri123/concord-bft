/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { TestBed, inject } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { of as observableOf } from 'rxjs';

import { AuthenticationService } from './authentication.service';
import { Personas, PersonaService } from './persona.service';
import { User } from '../users/shared/user.model';
import { UsersService } from '../users/shared/users.service';

describe('AuthenticationService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule, RouterTestingModule],
      providers: [AuthenticationService, PersonaService, UsersService]
    });
  });

  afterEach(inject([AuthenticationService], (service: AuthenticationService) => {
    service.logOut();
  }));

  it('should be created', inject([AuthenticationService], (service: AuthenticationService) => {
    expect(service).toBeTruthy();
  }));

  it('should broadcast an email after log in', inject([AuthenticationService], function(service: AuthenticationService) {
    const listResponse: User[] = [
      {
        consortium: { consortium_id: 2 },
        email: 'test.user@vmware.com'
      }];
    spyOn((service as any).usersService, 'getList')
         .and.returnValue(observableOf(listResponse));

    service.handleLogin({email: 'test@vmware.com'}, Personas.SystemsAdmin);

    const subscription = service.user.subscribe(user => {
      expect(user.email).toEqual('test@vmware.com');
    });

    subscription.unsubscribe();
  }));

  it('should broadcast undefined after log out', inject([AuthenticationService], function(service: AuthenticationService) {
    service.logOut();
    const subscription = service.user.subscribe(user => {
      expect(user.email).toBeUndefined();
    });
    subscription.unsubscribe();
  }));
});