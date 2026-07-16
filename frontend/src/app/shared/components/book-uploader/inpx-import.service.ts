import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';

export interface InpxBook {
  id: string;
  authors: string[];
  title: string;
  series: string;
  fileName: string;
  extension: string;
  archiveName: string;
}

export interface InpxSearchResult {
  books: InpxBook[];
  scannedCount: number;
  truncated: boolean;
}

export interface InpxImportResult {
  imported: number;
  skipped: number;
  failed: number;
  errors: string[];
}

export interface InpxImportRequest {
  inpxPath: string;
  archivePath: string | null;
  libraryId: number;
  libraryPathId: number;
  books: {
    archiveName: string;
    fileName: string;
    extension: string;
  }[];
}

@Injectable({providedIn: 'root'})
export class InpxImportService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/inpx`;

  search(inpxPath: string, query: string, limit = 200): Observable<InpxSearchResult> {
    const params = new HttpParams()
      .set('inpxPath', inpxPath)
      .set('query', query)
      .set('limit', limit);
    return this.http.get<InpxSearchResult>(`${this.url}/books`, {params});
  }

  importBooks(request: InpxImportRequest): Observable<InpxImportResult> {
    return this.http.post<InpxImportResult>(`${this.url}/import`, request);
  }
}
