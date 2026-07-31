import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {DjvuRenditionService} from './djvu-rendition.service';

describe('DjvuRenditionService', () => {
  let service: DjvuRenditionService;
  let httpTestingController: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [DjvuRenditionService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DjvuRenditionService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
  });

  it('reports a ready rendition', () => {
    let ready: boolean | undefined;
    service.isRenditionReady(42).subscribe(value => (ready = value));

    httpTestingController
      .expectOne(req => req.url.endsWith('/api/v1/djvu/42/rendition-status'))
      .flush({ready: true});

    expect(ready).toBe(true);
  });

  it('passes an alternative format through', () => {
    service.isRenditionReady(42, 'DJVU').subscribe();

    const request = httpTestingController.expectOne(req => req.url.includes('/api/v1/djvu/42/rendition-status'));
    expect(request.request.urlWithParams).toContain('bookType=DJVU');
    request.flush({ready: false});
  });

  it('treats an unreachable endpoint as not ready rather than failing the reader', () => {
    // The page reader needs nothing from this endpoint, so a book must still open without it.
    let ready: boolean | undefined;
    service.isRenditionReady(42).subscribe(value => (ready = value));

    httpTestingController
      .expectOne(req => req.url.endsWith('/api/v1/djvu/42/rendition-status'))
      .flush('boom', {status: 500, statusText: 'Server Error'});

    expect(ready).toBe(false);
  });
});
