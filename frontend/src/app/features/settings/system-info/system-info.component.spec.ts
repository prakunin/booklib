import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {TranslocoService} from '@jsverse/transloco';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {createQueryClientHarness, flushQueryAsync} from '../../../core/testing/query-testing';
import {API_CONFIG} from '../../../core/config/api-config';
import {SystemInfo} from '../../../core/services/system-info.service';
import {SystemInfoComponent} from './system-info.component';

const BASE_SNAPSHOT: SystemInfo = {
  application: {version: 'v1.2.3', springBootVersion: '4.0.0'},
  runtime: {
    javaVersion: '25', javaVendor: 'Eclipse Adoptium', jvmUptimeMillis: 1000,
    availableProcessors: 8, heapUsedBytes: 100, heapMaxBytes: 200,
  },
  os: {name: 'Linux', version: '6.17.0', arch: 'amd64'},
  database: {vendor: 'PostgreSQL', version: '16.1', status: 'UP'},
  storage: {diskType: 'LOCAL'},
  filesystems: [{paths: ['/data'], totalBytes: 1000, usableBytes: 500}],
  libraryPaths: [{path: '/books', status: 'OK'}],
  tools: {ffprobeVersion: '6.0', kepubifyVersion: '3.0'},
};

async function renderSnapshot(snapshot: SystemInfo) {
  const harness = createQueryClientHarness();

  await TestBed.configureTestingModule({
    imports: [SystemInfoComponent, getTranslocoModule()],
    providers: harness.providers,
  }).compileComponents();

  const fixture = TestBed.createComponent(SystemInfoComponent);
  fixture.componentRef.setInput('snapshot', snapshot);
  fixture.detectChanges();

  const t = TestBed.inject(TranslocoService);
  return {fixture, translate: (key: string) => t.translate(`settingsSystem.${key}`)};
}

describe('SystemInfoComponent', () => {
  it('renders a DOWN database without hiding the rest of the snapshot', async () => {
    const {fixture} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      database: {vendor: null, version: null, status: 'DOWN'},
      filesystems: [],
      libraryPaths: [{path: '/books/gone', status: 'MISSING'}],
      tools: {ffprobeVersion: null, kepubifyVersion: null},
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('DOWN');
    expect(text).toContain('v1.2.3');
    expect(text).toContain('/books/gone');
  });

  it('renders the not-available placeholder for a null database vendor and version', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      database: {vendor: null, version: null, status: 'UP'},
    });

    const text = fixture.nativeElement.textContent;
    const notAvailable = translate('notAvailable');
    expect(notAvailable).not.toBe('settingsSystem.notAvailable');
    expect(text.match(new RegExp(notAvailable, 'g'))?.length).toBe(2);
  });

  it('renders the not-available placeholder for both null tool versions', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      tools: {ffprobeVersion: null, kepubifyVersion: null},
    });

    const text = fixture.nativeElement.textContent;
    const notAvailable = translate('notAvailable');
    expect(text.match(new RegExp(notAvailable, 'g'))?.length).toBe(2);
  });

  it('renders the empty-filesystems note when no filesystems are reported', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      filesystems: [],
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain(translate('storage.noFilesystems'));
  });

  it('renders MISSING, UNREADABLE, UNKNOWN, and OK library path statuses', async () => {
    const {fixture} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      libraryPaths: [
        {path: '/books/missing', status: 'MISSING'},
        {path: '/books/unreadable', status: 'UNREADABLE'},
        {path: '/books/hung', status: 'UNKNOWN'},
        {path: '/books/ok', status: 'OK'},
      ],
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('MISSING');
    expect(text).toContain('UNREADABLE');
    expect(text).toContain('UNKNOWN');
    expect(text).toContain('OK');
    expect(text).toContain('/books/missing');
    expect(text).toContain('/books/unreadable');
    expect(text).toContain('/books/hung');
    expect(text).toContain('/books/ok');
  });

  it('shows an explanatory note when a library path is UNKNOWN', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      libraryPaths: [
        {path: '/books/hung', status: 'UNKNOWN'},
        {path: '/books/ok', status: 'OK'},
      ],
    });

    expect(fixture.nativeElement.textContent).toContain(translate('storage.pathStatusUnknownNote'));
  });

  it('does not show the UNKNOWN-path note when every path resolved', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      libraryPaths: [{path: '/books/ok', status: 'OK'}],
    });

    expect(fixture.nativeElement.textContent).not.toContain(translate('storage.pathStatusUnknownNote'));
  });

  it('formats heap usage in the gigabyte range instead of falling back to bytes', async () => {
    const {fixture} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      runtime: {
        ...BASE_SNAPSHOT.runtime,
        heapUsedBytes: 3 * 1024 ** 3,
        heapMaxBytes: 4 * 1024 ** 3,
      },
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('3.00 GB');
    expect(text).toContain('4.00 GB');
  });

  it('formats uptime spanning multiple days and hours', async () => {
    const twoDaysThreeHoursFourMinutesFiveSeconds = ((2 * 24 + 3) * 3600 + 4 * 60 + 5) * 1000;
    const {fixture} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      runtime: {
        ...BASE_SNAPSHOT.runtime,
        jvmUptimeMillis: twoDaysThreeHoursFourMinutesFiveSeconds,
      },
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('2d 3h 4m 5s');
  });

  it('renders "Not available" instead of "0 B" for the -1 heapMaxBytes sentinel', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      runtime: {
        ...BASE_SNAPSHOT.runtime,
        heapMaxBytes: -1,
      },
    });

    const text = fixture.nativeElement.textContent;
    const notAvailable = translate('notAvailable');
    // "100 B" (the heap-used side) legitimately contains the substring "0 B", so assert the
    // heap-max side precisely instead of a broad "does not contain '0 B'" check.
    expect(text).toContain(`/ ${notAvailable}`);
    expect(text).not.toContain('/ 0 B');
  });

  it('renders "Not available" for a null storage disk type instead of a blank value', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      storage: {diskType: null},
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain(translate('notAvailable'));
  });

  it('distinguishes "none configured" from "could not determine" for empty library paths', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      libraryPaths: [],
      database: {vendor: null, version: null, status: 'DOWN'},
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain(translate('storage.libraryPathsUnavailable'));
    expect(text).not.toContain(translate('storage.noLibraryPaths'));
  });

  it('reports "none configured" for empty library paths when the database is reachable', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      libraryPaths: [],
      database: {vendor: 'PostgreSQL', version: '16.1', status: 'UP'},
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain(translate('storage.noLibraryPaths'));
    expect(text).not.toContain(translate('storage.libraryPathsUnavailable'));
  });

  it('renders "Not available" for every field of a degraded application block, never a blank or "null"', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      application: {version: null, springBootVersion: null},
    });

    const text = fixture.nativeElement.textContent;
    const notAvailable = translate('notAvailable');
    expect(text).not.toContain('null');
    expect(text.match(new RegExp(notAvailable, 'g'))?.length).toBeGreaterThanOrEqual(2);
  });

  it('renders "Not available" for every field of a fully degraded runtime block, with no usage bar or percentage', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      runtime: {
        javaVersion: null,
        javaVendor: null,
        jvmUptimeMillis: null,
        availableProcessors: null,
        heapUsedBytes: null,
        heapMaxBytes: null,
      },
    });

    const text = fixture.nativeElement.textContent;
    const notAvailable = translate('notAvailable');
    expect(text).not.toContain('null');
    // javaVersion, javaVendor, processors, uptime, heapUsed, heapMax: six degraded fields.
    expect(text.match(new RegExp(notAvailable, 'g'))?.length).toBeGreaterThanOrEqual(6);
    // A degraded heap must not assert a measured "(0%)" or render a 0%-width bar.
    expect(text).not.toContain('(0%)');
    const heapBar = fixture.nativeElement.querySelector('.usage-bar-wide');
    expect(heapBar).toBeNull();
  });

  it('renders "Not available" for every field of a degraded OS block', async () => {
    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      os: {name: null, version: null, arch: null},
    });

    const text = fixture.nativeElement.textContent;
    const notAvailable = translate('notAvailable');
    expect(text).not.toContain('null');
    expect(text.match(new RegExp(notAvailable, 'g'))?.length).toBeGreaterThanOrEqual(3);
  });

  it('still renders a genuine 0% heap usage, distinct from an unavailable one', async () => {
    const {fixture} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      runtime: {...BASE_SNAPSHOT.runtime, heapUsedBytes: 0, heapMaxBytes: 200},
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('(0%)');
    const heapBar = fixture.nativeElement.querySelector('.usage-bar-wide');
    expect(heapBar).not.toBeNull();
  });

  it('copies a degraded snapshot as "Not available" text, never as the literal "null"', async () => {
    const clipboardWrite = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {writeText: clipboardWrite},
    });

    const {fixture, translate} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      application: {version: null, springBootVersion: null},
      runtime: {
        javaVersion: null,
        javaVendor: null,
        jvmUptimeMillis: null,
        availableProcessors: null,
        heapUsedBytes: null,
        heapMaxBytes: null,
      },
      os: {name: null, version: null, arch: null},
    });

    fixture.componentInstance['copy']();

    expect(clipboardWrite).toHaveBeenCalledTimes(1);
    const copiedText: string = clipboardWrite.mock.calls[0][0];
    expect(copiedText).not.toContain('null');
    expect(copiedText).toContain(translate('notAvailable'));
  });
});

describe('SystemInfoComponent driven by a real query (failed refresh)', () => {
  let httpTestingController: HttpTestingController;

  afterEach(() => {
    httpTestingController?.verify();
    TestBed.resetTestingModule();
  });

  it('keeps a failed refresh visible and marks the stale snapshot, instead of hiding the failure', async () => {
    const harness = createQueryClientHarness();
    harness.queryClient.setDefaultOptions({queries: {retry: false}});

    await TestBed.configureTestingModule({
      imports: [SystemInfoComponent, getTranslocoModule()],
      providers: harness.providers,
    }).compileComponents();

    httpTestingController = TestBed.inject(HttpTestingController);
    const t = TestBed.inject(TranslocoService);
    const translate = (key: string) => t.translate(`settingsSystem.${key}`);

    const fixture = TestBed.createComponent(SystemInfoComponent);
    fixture.detectChanges();

    const url = `${API_CONFIG.BASE_URL}/api/v1/admin/system-info`;
    httpTestingController.expectOne(url).flush(BASE_SNAPSHOT);
    await flushQueryAsync();

    // First load succeeded: no error, no stale marker yet.
    expect(fixture.nativeElement.textContent).toContain('v1.2.3');
    expect(fixture.nativeElement.textContent).not.toContain(translate('refreshError'));

    fixture.componentInstance['refresh']();
    httpTestingController.expectOne(url).flush('boom', {status: 500, statusText: 'Server Error'});
    await flushQueryAsync();

    const text = fixture.nativeElement.textContent;
    // The last successful snapshot is still shown (TanStack Query does not clear `data` on error)...
    expect(text).toContain('v1.2.3');
    // ...but the failed refresh must be visible, not silently swallowed behind the stale snapshot.
    expect(text).toContain(translate('refreshError'));
    expect(text).toContain(translate('staleDataNote'));
    const staleContainer = fixture.nativeElement.querySelector('.settings-content-stale');
    expect(staleContainer).not.toBeNull();
  });
});
