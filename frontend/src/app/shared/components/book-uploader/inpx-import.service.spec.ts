import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';
import {InpxImportService, InpxSearchResult} from './inpx-import.service';

describe('InpxImportService', () => {
  let service: InpxImportService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(InpxImportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('searches an INPX index with server-side parameters', () => {
    const response: InpxSearchResult = {books: [], scannedCount: 12, truncated: false};

    service.search('/catalog/library.inpx', 'автор').subscribe(result => {
      expect(result).toEqual(response);
    });

    const request = http.expectOne(req => req.url.endsWith('/api/v1/inpx/books'));
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('inpxPath')).toBe('/catalog/library.inpx');
    expect(request.request.params.get('query')).toBe('автор');
    expect(request.request.params.get('limit')).toBe('200');
    request.flush(response);
  });

  it('imports only the selected archive entries', () => {
    const body = {
      inpxPath: '/catalog/library.inpx',
      archivePath: '/catalog',
      libraryId: 7,
      libraryPathId: 9,
      books: [{archiveName: 'fb2-1-100.zip', fileName: '42', extension: 'fb2'}]
    };

    service.importBooks(body).subscribe(result => {
      expect(result.imported).toBe(1);
    });

    const request = http.expectOne(req => req.url.endsWith('/api/v1/inpx/import'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush({imported: 1, skipped: 0, failed: 0, errors: []});
  });
});
