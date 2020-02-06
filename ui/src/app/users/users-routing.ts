/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { Routes } from '@angular/router';

import { ConsortiumComponent } from './../consortium/consortium.component';
import { EnvironmentComponent } from '../environment/environment/environment.component';
import { FeaturesComponent } from '../features/features/features.component';
import { TasksComponent } from '../tasks/tasks/tasks.component';
import { UserSettingsComponent } from './user-settings/user-settings.component';
import { UsersComponent } from './users/users.component';

export const usersRoutes: Routes = [
  { path: 'consortiums', component: ConsortiumComponent },
  {
    path: 'tasks', component: TasksComponent
  },
  {
    path: 'features', component: FeaturesComponent
  },
  {
    path: 'roles', component: UsersComponent
  },
  {
    path: 'environment', component: EnvironmentComponent
  },
  {
    path: 'settings',
    component: UserSettingsComponent
  }
];
