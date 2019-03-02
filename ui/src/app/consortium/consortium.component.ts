/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import {
  Component,
  OnInit,
  ViewChild,
} from '@angular/core';

import { Personas } from '../shared/persona.service';
import { ConsortiumFormComponent } from './consortium-form/consortium-form.component';
import { ConsortiumListComponent } from './consortium-list/consortium-list.component';
import { Consortium } from './shared/consortium.model';

@Component({
  selector: 'concord-consortium',
  templateUrl: './consortium.component.html',
  styleUrls: ['./consortium.component.scss']
})
export class ConsortiumComponent implements OnInit {
  static personasAllowed: Personas[] = [Personas.SystemsAdmin, Personas.ConsortiumAdmin];
  @ViewChild('consortiumForm') consortiumForm: ConsortiumFormComponent;
  @ViewChild('consortiumsList') consortiumsList: ConsortiumListComponent;

  selected: Array<Consortium>;

  constructor() {}

  ngOnInit() {}

  selectedRowChange(rows: Array<Consortium>): void {
    this.selected = rows;
  }

  addToConsortiumsList(response) {
    this.consortiumsList.grid.addRow(response);
  }

  deleteFromConsortiumsList() {
    this.consortiumsList.grid.reload();
  }
}