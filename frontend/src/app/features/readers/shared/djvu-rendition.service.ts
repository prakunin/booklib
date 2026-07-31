import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {catchError, map, Observable, of} from 'rxjs';

import {API_CONFIG} from '../../../core/config/api-config';

export interface DjvuRenditionStatus {
  ready: boolean;
}

/**
 * Asks whether a DjVu book's searchable PDF rendition can be opened yet.
 *
 * Asking is also what starts it being built: a DjVu book always opens straight away in the page
 * reader, and the rendition — which adds searchable, selectable text and annotations — is built in
 * the background from the first time someone opens the book.
 */
@Injectable({
  providedIn: 'root'
})
export class DjvuRenditionService {
  private readonly http = inject(HttpClient);

  /**
   * Never fails the caller: a reader that cannot reach this endpoint must still open the book in
   * the page reader, which needs nothing from it.
   */
  isRenditionReady(bookId: number, bookType?: string): Observable<boolean> {
    const url = bookType
      ? `${API_CONFIG.BASE_URL}/api/v1/djvu/${bookId}/rendition-status?bookType=${bookType}`
      : `${API_CONFIG.BASE_URL}/api/v1/djvu/${bookId}/rendition-status`;

    return this.http.get<DjvuRenditionStatus>(url).pipe(
      map(status => status.ready),
      catchError(() => of(false))
    );
  }
}
