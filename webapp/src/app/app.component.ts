/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Component, OnDestroy, NgZone } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs/Subscription';

import { AuthenticationService } from './shared/authentication.service';
import { ErrorAlertService } from './shared/global-error-handler.service';
import { Personas, PersonaService } from './shared/persona.service';
import { TranslateService } from '@ngx-translate/core';
import { FormGroup, FormControl } from '@angular/forms';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent implements OnDestroy {
  title = 'app';
  alerts: any = [];
  authenticationChange: Subscription;

  authenticated = false;
  username: string;
  personaFormGroup: FormGroup;

  private personaOptions: Array<{ name ?: string; value: string; }> = [
    { value: Personas.SystemsAdmin, name: 'Systems Admin' },
    { value: Personas.ConsortiumAdmin, name: 'Consortium Admin' },
    { value: Personas.OrgAdmin, name: 'Org Admin' },
    { value: Personas.OrgDeveloper, name: 'Org Developer' },
    { value: Personas.OrgUser, name: 'Org User' },
  ];

  constructor(
    private authenticationService: AuthenticationService,
    private router: Router,
    private alertService: ErrorAlertService,
    public zone: NgZone,
    private translate: TranslateService,
    private personaService: PersonaService
  ) {
    const browserLang = translate.getBrowserLang();

    translate.setDefaultLang('en');
    translate.use(browserLang);

    this.personaFormGroup = new FormGroup({
      persona: new FormControl(this.personaService.currentPersona)
    });

    this.authenticationChange = authenticationService.user.subscribe(user => {
      this.authenticated = user.email !== undefined && user.persona !== undefined;
      this.username = user.email;
      this.personaService.currentPersona = user.persona;
      this.personaFormGroup.patchValue({ persona: user.persona });
    });

    this.alertService.notify
      .subscribe(error => this.addAlert(error));

  }

  ngOnDestroy(): void {
    this.authenticationChange.unsubscribe();
  }

  onPersonaChange() {
    this.personaService.currentPersona = this.personaFormGroup.value;
  }

  onLogOut() {
    this.authenticationService.logOut();
    this.router.navigate(['auth', 'log-in']);
  }

  private addAlert(alert: any): void {
    if (alert && alert.message) {
      const alertItem = {
        message: alert.message
      };
      if (this.alerts.indexOf(alertItem) === -1) {
        this.zone.run(() => this.alerts.push(alertItem));
      }
    }
  }
}
