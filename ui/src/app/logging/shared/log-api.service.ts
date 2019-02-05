/*
 * Copyright 2018-2019 VMware, all rights reserved.
 */

import { Inject, Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { mergeMap } from 'rxjs/operators';

import { LOG_API_PREFIX } from '../../shared/shared.config';
import { CspApiService } from '../../shared/csp-api.service';
import { LogTaskResponse, LogTaskCompletedResponse, LogTaskParams } from './logging.model';

@Injectable({
  providedIn: 'root'
})
export class LogApiService {

  constructor(@Inject(LOG_API_PREFIX) private logApiPrefix: string, private httpClient: HttpClient, private cspApi: CspApiService) {}

  postToTasks(start: number, end: number, rows: number = 20): Observable<LogTaskResponse> {
    const logQuery = {
      logQuery: 'SELECT * FROM logs ORDER BY ingest_timestamp DESC',
      start: start,
      end: end,
      rows: rows
    };
    return this.logQueryTask(logQuery);
  }

  postToTasksCount(start: number, end: number, interval: number): Observable<LogTaskResponse> {
    const logQuery = {
      logQuery: `SELECT COUNT(*), timestamp FROM logs GROUP BY bucket(timestamp, ${interval}, ${start}, ${end}) ORDER BY timestamp DESC`,
      start: start,
      end: end
    };
    return this.logQueryTask(logQuery);
  }

  postToPureCount(start: number, end: number): Observable<LogTaskResponse> {
    const logQuery = {
      logQuery: 'SELECT COUNT(*) FROM logs',
      start: start,
      end: end
    };
    return this.logQueryTask(logQuery);
  }

  fetchLogStatus(path: string): Observable<LogTaskCompletedResponse> {
    return this.cspApi.fetchToken().pipe(mergeMap(((tokenResp: any) => {
      return this.httpClient.get<LogTaskCompletedResponse>(`${this.logApiPrefix}${path}`, {
        headers: new HttpHeaders()
          .set('Authorization', `Bearer ${tokenResp.access_token}`)
      });
    })));
  }

  private logQueryTask(logQuery: LogTaskParams): Observable<LogTaskResponse> {
    return this.cspApi.fetchToken().pipe(mergeMap(((tokenResp: any) => {
      return this.httpClient.post<LogTaskResponse>(`${this.logApiPrefix}/ops/query/log-query-tasks`, logQuery, {
        headers: new HttpHeaders()
          .set('Authorization', `Bearer ${tokenResp.access_token}`)
      });
    })));
  }
}

