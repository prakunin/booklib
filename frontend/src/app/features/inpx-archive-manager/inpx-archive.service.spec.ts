import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';
import {InpxArchiveService} from './inpx-archive.service';
import type {InpxArchive, InpxArchiveScanTask} from './inpx-archive.model';

describe('InpxArchiveService', () => {
  let service: InpxArchiveService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), InpxArchiveService],
    });
    service = TestBed.inject(InpxArchiveService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('loads archive statistics for a library', () => {
    const archives = [{archiveName: 'new.zip'}] as InpxArchive[];
    let received: InpxArchive[] | undefined;

    service.getArchives(7).subscribe(value => (received = value));

    const request = http.expectOne(req => req.url.endsWith('/api/v1/inpx/libraries/7/archives'));
    expect(request.request.method).toBe('GET');
    request.flush(archives);
    expect(received).toEqual(archives);
  });

  it('starts a full scan for one archive', () => {
    service.rescan(7, 'new.zip').subscribe();

    const request = http.expectOne(req => req.url.endsWith('/api/v1/inpx/libraries/7/archives/rescan'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({archiveName: 'new.zip'});
    request.flush(null);
  });

  it('loads the archive scan queue', () => {
    const tasks = [{archiveName: 'new.zip'}] as InpxArchiveScanTask[];
    let received: InpxArchiveScanTask[] | undefined;

    service.getScanQueue(7).subscribe(value => (received = value));

    const request = http.expectOne(req => req.url.endsWith('/api/v1/inpx/libraries/7/archive-scans'));
    expect(request.request.method).toBe('GET');
    request.flush(tasks);
    expect(received).toEqual(tasks);
  });
});
