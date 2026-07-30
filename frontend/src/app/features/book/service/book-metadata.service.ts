import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';
import {FetchMetadataRequest} from '../../metadata/model/request/fetch-metadata-request.model';
import {BookMetadata} from '../model/book.model';
import {AuthService} from '../../../shared/service/auth.service';
import {HttpClient} from '@angular/common/http';
import {streamServerSentEvents} from '../../../shared/service/sse-stream';

@Injectable({providedIn: 'root'})
export class BookMetadataService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books`;
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  fetchBookMetadata(bookId: number, request: FetchMetadataRequest): Observable<BookMetadata> {
    const token = this.authService.getInternalAccessToken();

    if (!token) {
      throw new Error('No authentication token available');
    }

    return streamServerSentEvents<BookMetadata>(
      `${this.url}/${bookId}/metadata/prospective`,
      token,
      {body: request}
    );
  }

  fetchMetadataDetail(provider: string, providerItemId: string): Observable<BookMetadata> {
    return this.http.get<BookMetadata>(`${this.url}/metadata/detail/${provider}/${providerItemId}`);
  }

  lookupByIsbn(isbn: string): Observable<BookMetadata> {
    return this.http.post<BookMetadata>(`${this.url}/metadata/isbn-lookup`, {isbn});
  }
}
