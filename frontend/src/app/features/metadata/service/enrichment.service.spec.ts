import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {EnrichmentService} from './enrichment.service';
import {EnrichmentRequest} from '../model/enrichment.model';

describe('EnrichmentService', () => {
  let service: EnrichmentService;
  let httpTestingController: HttpTestingController;

  const request: EnrichmentRequest = {
    scope: 'BOOKS',
    bookIds: [1, 2],
    steps: ['LOCAL_CATALOG', 'PROVIDERS'],
    writePolicy: 'AUTO_IF_EMPTY',
    agentAllowed: false,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), EnrichmentService],
    });
    service = TestBed.inject(EnrichmentService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('posts the request and returns the job id', () => {
    let jobId: string | undefined;
    service.enrich(request).subscribe((job) => (jobId = job.jobId));

    const call = httpTestingController.expectOne((req) => req.url.endsWith('/api/v1/enrichment'));
    expect(call.request.method).toBe('POST');
    expect(call.request.body).toEqual(request);
    call.flush({jobId: 'job-1'});

    expect(jobId).toBe('job-1');
  });

  it('reads job progress', () => {
    let outstanding: number | undefined;
    service.progress('job-1').subscribe((progress) => (outstanding = progress.outstanding));

    const call = httpTestingController.expectOne((req) => req.url.endsWith('/api/v1/enrichment/jobs/job-1'));
    expect(call.request.method).toBe('GET');
    call.flush({jobId: 'job-1', total: 5, done: 2, skipped: 0, failed: 0, cancelled: 0, outstanding: 3, finished: false});

    expect(outstanding).toBe(3);
  });

  it('cancels a job', () => {
    let cancelled: number | undefined;
    service.cancel('job-1').subscribe((result) => (cancelled = result.cancelled));

    const call = httpTestingController.expectOne((req) => req.url.endsWith('/api/v1/enrichment/jobs/job-1/cancel'));
    expect(call.request.method).toBe('POST');
    call.flush({cancelled: 4});

    expect(cancelled).toBe(4);
  });

  it('asks for a local catalog next to a given archive directory', () => {
    let detected: string | null | undefined;
    service.detectLocalCatalog('/books/fb2.Flibusta.Net').subscribe((result) => (detected = result.path));

    const call = httpTestingController.expectOne((req) => req.url.endsWith('/api/v1/enrichment/local-catalog/detect'));
    expect(call.request.params.get('archivePath')).toBe('/books/fb2.Flibusta.Net');
    call.flush({path: '/books/fb2.Flibusta.Net.FLibrary.etc'});

    expect(detected).toBe('/books/fb2.Flibusta.Net.FLibrary.etc');
  });

  it('triggers a local catalog reindex', () => {
    let started: boolean | undefined;
    service.reindexLocalCatalog(3).subscribe((result) => (started = result.started));

    const call = httpTestingController.expectOne((req) => req.url.endsWith('/api/v1/enrichment/local-catalog/3/reindex'));
    expect(call.request.method).toBe('POST');
    call.flush({started: true});

    expect(started).toBe(true);
  });
});
