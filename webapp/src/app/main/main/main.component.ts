/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Component, NgZone, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthenticationService } from '../../shared/authentication.service';
import { ErrorAlertService } from '../../shared/global-error-handler.service';
import { Personas, PersonaService } from '../../shared/persona.service';
import { TaskManagerService } from '../../shared/task-manager.service';

@Component({
  selector: 'athena-main',
  templateUrl: './main.component.html',
  styleUrls: ['./main.component.scss']
})
export class MainComponent implements OnDestroy {
  alerts: any = [];
  authenticationChange: Subscription;

  authenticated = false;
  username: string;
  personas = Personas;


  personaOptions: Array<{ name ?: string; value: Personas; }> = [
    { value: Personas.SystemsAdmin, name: 'personas.systemsAdmin' },
    { value: Personas.ConsortiumAdmin, name: 'personas.consortiumAdmin' },
    { value: Personas.OrgAdmin, name: 'personas.orgAdmin' },
    { value: Personas.OrgDeveloper, name: 'personas.orgDeveloper' },
    { value: Personas.OrgUser, name: 'personas.orgUser' }
  ];

  constructor(
    private authenticationService: AuthenticationService,
    private router: Router,
    private alertService: ErrorAlertService,
    public zone: NgZone,
    private personaService: PersonaService,
    private taskManagerService: TaskManagerService
  ) {
    this.authenticationChange = authenticationService.user.subscribe(user => {
      this.authenticated = user.email !== undefined && user.persona !== undefined;
      this.username = user.email;
      this.personaService.currentPersona = user.persona;
    });

    this.alertService.notify
      .subscribe(error => this.addAlert(error));
  }

  ngOnDestroy(): void {
    this.authenticationChange.unsubscribe();
  }

  onPersonaChange(persona: Personas) {
    this.personaService.currentPersona = persona;
    location.reload();
  }

  onLogOut() {
    this.authenticationService.logOut();
    this.router.navigate(['auth', 'log-in']);
  }

  onResetTasks() {
    this.taskManagerService.resetTasks();
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
