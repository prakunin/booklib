import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {HttpClient, HttpParams} from '@angular/common/http';
import {API_CONFIG} from '../../../core/config/api-config';

export interface InpxIndexOption {
  path: string;
  fileName: string;
  sizeBytes: number;
}

@Injectable({
  providedIn: 'root'
})
export class UtilityService {

  private readonly pathUrl = `${API_CONFIG.BASE_URL}/api/v1/path`;

  private readonly http = inject(HttpClient);

  getFolders(path: string): Observable<string[]> {
    const params = new HttpParams().set('path', path);
    return this.http.get<string[]>(this.pathUrl, {params});
  }

  getInpxFiles(path: string): Observable<InpxIndexOption[]> {
    const params = new HttpParams().set('path', path);
    return this.http.get<InpxIndexOption[]>(`${this.pathUrl}/inpx`, {params});
  }
}
