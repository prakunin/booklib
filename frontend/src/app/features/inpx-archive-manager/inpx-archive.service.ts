import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../core/config/api-config';
import {InpxArchive, InpxArchiveScanTask} from './inpx-archive.model';

@Injectable({providedIn: 'root'})
export class InpxArchiveService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/inpx/libraries`;

  getArchives(libraryId: number): Observable<InpxArchive[]> {
    return this.http.get<InpxArchive[]>(`${this.url}/${libraryId}/archives`);
  }

  getScanQueue(libraryId: number): Observable<InpxArchiveScanTask[]> {
    return this.http.get<InpxArchiveScanTask[]>(`${this.url}/${libraryId}/archive-scans`);
  }

  rescan(libraryId: number, archiveName: string): Observable<void> {
    return this.http.post<void>(`${this.url}/${libraryId}/archives/rescan`, {archiveName});
  }
}
