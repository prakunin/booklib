import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';
import {API_CONFIG} from '../config/api-config';

export type PathStatus = 'OK' | 'MISSING' | 'UNREADABLE' | 'UNKNOWN';

export interface SystemInfo {
  // Every field below that the backend can report as a bare, unguarded builder default (e.g. when
  // VersionService or ManagementFactory throws) is nullable here too — a non-null type would just
  // relocate the bug instead of fixing it: the value would still be `null` at runtime, only
  // silently, wherever a template or the copy-text builder forgot a fallback.
  application: {version: string | null; springBootVersion: string | null};
  runtime: {
    javaVersion: string | null;
    javaVendor: string | null;
    jvmUptimeMillis: number | null;
    availableProcessors: number | null;
    heapUsedBytes: number | null;
    heapMaxBytes: number | null;
  };
  os: {name: string | null; version: string | null; arch: string | null};
  database: {vendor: string | null; version: string | null; status: 'UP' | 'DOWN'};
  storage: {diskType: string | null};
  filesystems: {paths: string[]; totalBytes: number; usableBytes: number}[];
  libraryPaths: {path: string; status: PathStatus}[];
  tools: {ffprobeVersion: string | null; kepubifyVersion: string | null};
}

@Injectable({providedIn: 'root'})
export class SystemInfoService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/admin/system-info`;

  query() {
    return injectQuery(() => ({
      queryKey: ['system-info'],
      queryFn: () => lastValueFrom(this.http.get<SystemInfo>(this.url)),
    }));
  }
}
