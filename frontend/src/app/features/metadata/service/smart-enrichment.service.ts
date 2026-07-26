import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {map, Observable, of} from 'rxjs';
import {catchError, shareReplay} from 'rxjs/operators';
import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {streamServerSentEvents} from '../../../shared/service/sse-stream';
import {SmartEnrichmentEvent} from '../model/smart-enrichment.model';

@Injectable({providedIn: 'root'})
export class SmartEnrichmentService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books`;
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  /**
   * Whether the instance has the agent binary at all. Cached for the session: it depends on
   * deployment, which cannot change while the app is loaded. A failed check reads as unavailable,
   * so a missing endpoint on an older backend simply hides the button.
   */
  readonly available$: Observable<boolean> = this.http
    .get<{enabled: boolean}>(`${this.url}/metadata/smart-enrich/availability`)
    .pipe(
      map((response) => response.enabled),
      catchError(() => of(false)),
      shareReplay({bufferSize: 1, refCount: false})
    );

  enrich(bookId: number): Observable<SmartEnrichmentEvent> {
    const token = this.authService.getInternalAccessToken();
    if (!token) {
      throw new Error('No authentication token available');
    }
    return streamServerSentEvents<SmartEnrichmentEvent>(
      `${this.url}/${bookId}/metadata/smart-enrich`,
      token
    );
  }
}
