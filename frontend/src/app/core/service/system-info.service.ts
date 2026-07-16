import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';

export type PathStatus = 'OK' | 'MISSING' | 'UNREADABLE';

export interface SystemInfo {
  application: {version: string; springBootVersion: string};
  runtime: {
    javaVersion: string;
    javaVendor: string;
    jvmUptimeMillis: number;
    availableProcessors: number;
    heapUsedBytes: number;
    heapMaxBytes: number;
  };
  os: {name: string; version: string; arch: string};
  database: {vendor: string | null; version: string | null; status: 'UP' | 'DOWN'};
  storage: {diskType: string};
  filesystems: {paths: string[]; totalBytes: number; usableBytes: number}[];
  libraryPaths: {path: string; status: PathStatus}[];
  tools: {ffprobeVersion: string | null; kepubifyVersion: string | null};
}

@Injectable({providedIn: 'root'})
export class SystemInfoService {
  private http = inject(HttpClient);

  query() {
    return injectQuery(() => ({
      queryKey: ['system-info'],
      queryFn: () => lastValueFrom(this.http.get<SystemInfo>('/api/v1/admin/system-info')),
    }));
  }
}
