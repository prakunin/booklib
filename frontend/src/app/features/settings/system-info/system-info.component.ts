import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {Button} from 'primeng/button';
import {Toast} from 'primeng/toast';
import {MessageService} from 'primeng/api';
import {PathStatus, SystemInfo, SystemInfoService} from '../../../core/services/system-info.service';
import {TagColor, TagComponent} from '../../../shared/components/tag/tag.component';

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

const PATH_STATUS_COLOR: Record<PathStatus, TagColor> = {
  OK: 'green',
  MISSING: 'red',
  UNREADABLE: 'amber',
  // Neither healthy (green) nor confirmed broken (red/amber): the probe simply could not answer
  // within its time budget (e.g. a hung network mount), so this reads as neutral/unresolved.
  UNKNOWN: 'gray',
};

@Component({
  selector: 'app-system-info',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Button, Toast, TranslocoDirective, TagComponent],
  providers: [MessageService],
  templateUrl: './system-info.component.html',
  styleUrl: './system-info.component.scss',
})
export class SystemInfoComponent {
  private systemInfoService = inject(SystemInfoService);
  private messageService = inject(MessageService);
  private t = inject(TranslocoService);

  /** Lets tests drive the view directly, bypassing the live query. */
  snapshot = input<SystemInfo>();

  private query = this.systemInfoService.query();

  protected data = computed<SystemInfo | undefined>(() => this.snapshot() ?? this.query.data());
  protected loading = computed(() => !this.snapshot() && this.query.isFetching());
  protected error = computed(() => !this.snapshot() && this.query.isError());
  // TanStack Query keeps the last successful `data` around when a later fetch fails, so a failed
  // refresh must stay visible (and the shown data marked stale) even though `data()` is non-empty.
  protected stale = computed(() => this.error() && !!this.data());

  protected refresh(): void {
    void this.query.refetch();
  }

  protected copy(): void {
    const info = this.data();
    if (!info) {
      return;
    }
    const text = this.buildCopyText(info);
    navigator.clipboard.writeText(text).then(() => {
      this.messageService.add({
        severity: 'success',
        summary: this.t.translate('common.success'),
        detail: this.t.translate('settingsSystem.copied'),
      });
    }).catch(err => {
      console.error('Copy failed', err);
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('common.error'),
        detail: this.t.translate('settingsSystem.copyFailed'),
      });
    });
  }

  protected pathStatusColor(status: PathStatus): TagColor {
    return PATH_STATUS_COLOR[status] ?? 'gray';
  }

  /**
   * An empty libraryPaths list is ambiguous: it can mean "none configured" or "the database was
   * unreachable and we could not determine them". The backend already knows which via
   * database.status, so use that instead of presenting a probe failure as configuration state.
   */
  protected noLibraryPathsKey(info: SystemInfo): string {
    return info.database.status === 'DOWN' ? 'storage.libraryPathsUnavailable' : 'storage.noLibraryPaths';
  }

  /**
   * A path probe that could not be determined within its time budget (e.g. a hung network mount)
   * is reported as UNKNOWN rather than silently folded into MISSING or OK. Surface an explanatory
   * note whenever at least one path carries that status, mirroring how `database.downNote` explains
   * a DOWN database instead of leaving the admin to guess why a status looks unusual.
   */
  protected hasUnknownLibraryPath(info: SystemInfo): boolean {
    return info.libraryPaths.some(lp => lp.status === 'UNKNOWN');
  }

  /**
   * `null` means "do not show a percentage or a usage bar at all", distinct from a genuine `0`:
   * heapMaxBytes is `-1` when the JVM defines no maximum (no percentage is meaningful then), and
   * `null` when the whole runtime block is degraded (heapUsedBytes is unknown too, in that case).
   * A caller must not render "(0%)" plus a 0%-width bar for either case — that would assert a
   * measurement that was never taken.
   */
  protected heapUsagePercent(runtime: SystemInfo['runtime']): number | null {
    const max = runtime.heapMaxBytes;
    const used = runtime.heapUsedBytes;
    if (max === null || max === undefined || max <= 0 || used === null || used === undefined) {
      return null;
    }
    return Math.min(100, Math.max(0, Math.round((used / max) * 100)));
  }

  protected filesystemUsagePercent(fs: {totalBytes: number; usableBytes: number}): number {
    if (!fs.totalBytes) {
      return 0;
    }
    const used = fs.totalBytes - fs.usableBytes;
    return Math.min(100, Math.max(0, Math.round((used / fs.totalBytes) * 100)));
  }

  protected formatBytes(bytes: number | null | undefined): string {
    // The backend deliberately passes heapMaxBytes = -1 through when the JVM defines no maximum:
    // that is "not available", not "0 bytes", so a negative value must not be clamped up to 0.
    if (bytes === null || bytes === undefined || Number.isNaN(bytes) || bytes < 0) {
      return this.t.translate('settingsSystem.notAvailable');
    }
    if (bytes === 0) {
      return `0 ${BYTE_UNITS[0]}`;
    }
    const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), BYTE_UNITS.length - 1);
    const value = bytes / Math.pow(1024, exponent);
    return `${value.toFixed(exponent === 0 ? 0 : 2)} ${BYTE_UNITS[exponent]}`;
  }

  protected formatUptime(millis: number | null | undefined): string {
    if (millis === null || millis === undefined || Number.isNaN(millis) || millis < 0) {
      return this.t.translate('settingsSystem.notAvailable');
    }
    const totalSeconds = Math.floor(millis / 1000);
    const days = Math.floor(totalSeconds / 86400);
    const hours = Math.floor((totalSeconds % 86400) / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    const parts: string[] = [];
    if (days) parts.push(`${days}d`);
    if (days || hours) parts.push(`${hours}h`);
    if (days || hours || minutes) parts.push(`${minutes}m`);
    parts.push(`${seconds}s`);
    return parts.join(' ');
  }

  private buildCopyText(info: SystemInfo): string {
    const tr = (key: string) => this.t.translate(`settingsSystem.${key}`);
    const na = tr('notAvailable');
    const lines: string[] = [];

    lines.push(tr('title'));
    lines.push(`${tr('application.title')}: ${info.application.version ?? na} (${tr('application.springBootVersion')} ${info.application.springBootVersion ?? na})`);
    lines.push(`${tr('runtime.title')}: ${tr('runtime.javaVersion')} ${info.runtime.javaVersion ?? na} (${info.runtime.javaVendor ?? na}), `
      + `${tr('runtime.uptime')} ${this.formatUptime(info.runtime.jvmUptimeMillis)}, `
      + `${tr('runtime.processors')} ${info.runtime.availableProcessors ?? na}`);
    lines.push(`${tr('runtime.heap')}: ${this.formatBytes(info.runtime.heapUsedBytes)} / ${this.formatBytes(info.runtime.heapMaxBytes)}`);
    lines.push(`${tr('os.title')}: ${info.os.name ?? na} ${info.os.version ?? na} (${info.os.arch ?? na})`);
    lines.push(`${tr('database.title')}: ${info.database.status}`
      + (info.database.vendor ? ` - ${info.database.vendor} ${info.database.version ?? ''}` : ''));
    lines.push(`${tr('storage.title')}: ${info.storage.diskType ?? na}`);

    if (info.filesystems.length) {
      lines.push(`${tr('storage.filesystems')}:`);
      for (const fs of info.filesystems) {
        lines.push(`  ${fs.paths.join(', ')}: ${this.formatBytes(fs.usableBytes)} ${tr('storage.free')} / `
          + `${this.formatBytes(fs.totalBytes)} ${tr('storage.total')}`);
      }
    } else {
      lines.push(`${tr('storage.filesystems')}: ${tr('storage.noFilesystems')}`);
    }

    if (info.libraryPaths.length) {
      lines.push(`${tr('storage.libraryPaths')}:`);
      for (const lp of info.libraryPaths) {
        lines.push(`  [${lp.status}] ${lp.path}`);
      }
    } else {
      lines.push(`${tr('storage.libraryPaths')}: ${tr(this.noLibraryPathsKey(info))}`);
    }

    lines.push(`${tr('tools.title')}: ffprobe ${info.tools.ffprobeVersion ?? na}, kepubify ${info.tools.kepubifyVersion ?? na}`);

    return lines.join('\n');
  }
}
