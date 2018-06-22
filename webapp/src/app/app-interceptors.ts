/*
 * Copyright 2018 VMware, all rights reserved.
 */

import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor, HttpResponse,
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { tap } from 'rxjs/operators';

/**
 * Checks for successful HTTP responses (status code 2XX) that are actually errors and throws.
 *
 * The Ethereum API endpoint is currently returning error responses as HTTP status code 200. Once this is resolved,
 * this can be removed.
 */
@Injectable()
export class RequestInterceptor implements HttpInterceptor {
  intercept(request: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    return next.handle(request).pipe(tap((event: HttpEvent<any>) => {
      // Make sure this is a response from the Ethereum endpoint and that there is an error present
      if (event instanceof HttpResponse && event.url.indexOf('/api/athena/eth') !== -1 && event.body.error) {
        throw throwError(event.body.error.message);
      }
    }));
  }
}
