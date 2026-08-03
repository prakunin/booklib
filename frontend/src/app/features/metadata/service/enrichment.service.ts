import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';
import {
  EnrichmentJob,
  EnrichmentProgress,
  EnrichmentQueueOverview,
  EnrichmentRequest,
} from '../model/enrichment.model';

@Injectable({providedIn: 'root'})
export class EnrichmentService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/enrichment`;
  private readonly http = inject(HttpClient);

  /**
   * Returns as soon as the work is queued, not when it is done: even a single book can involve
   * provider calls measured in seconds and an agent call measured in minutes. Progress arrives over
   * the websocket.
   */
  enrich(request: EnrichmentRequest): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(this.url, request);
  }

  progress(jobId: string): Observable<EnrichmentProgress> {
    return this.http.get<EnrichmentProgress>(`${this.url}/jobs/${jobId}`);
  }

  /**
   * Cancels what has not started. A book already being enriched runs to completion.
   */
  cancel(jobId: string): Observable<{cancelled: number}> {
    return this.http.post<{cancelled: number}>(`${this.url}/jobs/${jobId}/cancel`, {});
  }

  queueOverview(): Observable<EnrichmentQueueOverview> {
    return this.http.get<EnrichmentQueueOverview>(`${this.url}/queue`);
  }

  detectLocalCatalog(archivePath: string): Observable<{path: string | null}> {
    return this.http.get<{path: string | null}>(`${this.url}/local-catalog/detect`, {
      params: {archivePath},
    });
  }

  reindexLocalCatalog(libraryId: number): Observable<{started: boolean}> {
    return this.http.post<{started: boolean}>(`${this.url}/local-catalog/${libraryId}/reindex`, {});
  }
}
