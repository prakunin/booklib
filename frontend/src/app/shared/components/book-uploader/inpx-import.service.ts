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

/**
 * Where an index is read from. A registered INPX library is preferred; a raw path is
 * administrator-only, because the server has no allowlist for client-supplied paths.
 */
export interface InpxSource {
  sourceLibraryId?: number | null;
  inpxPath?: string | null;
  archivePath?: string | null;
}

export interface InpxImportRequest extends InpxSource {
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

  search(source: InpxSource, query: string, limit = 200): Observable<InpxSearchResult> {
    let params = new HttpParams()
      .set('query', query)
      .set('limit', limit);
    if (source.sourceLibraryId != null) {
      params = params.set('sourceLibraryId', source.sourceLibraryId);
    } else if (source.inpxPath) {
      params = params.set('inpxPath', source.inpxPath);
    }
    return this.http.get<InpxSearchResult>(`${this.url}/books`, {params});
  }

  importBooks(libraryId: number, request: InpxImportRequest): Observable<InpxImportResult> {
    return this.http.post<InpxImportResult>(`${this.url}/libraries/${libraryId}/import`, request);
  }
}
