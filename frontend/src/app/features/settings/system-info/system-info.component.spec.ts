import {TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {describe, expect, it} from 'vitest';
import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {createQueryClientHarness} from '../../../core/testing/query-testing';
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

  it('renders MISSING, UNREADABLE, and OK library path statuses', async () => {
    const {fixture} = await renderSnapshot({
      ...BASE_SNAPSHOT,
      libraryPaths: [
        {path: '/books/missing', status: 'MISSING'},
        {path: '/books/unreadable', status: 'UNREADABLE'},
        {path: '/books/ok', status: 'OK'},
      ],
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('MISSING');
    expect(text).toContain('UNREADABLE');
    expect(text).toContain('OK');
    expect(text).toContain('/books/missing');
    expect(text).toContain('/books/unreadable');
    expect(text).toContain('/books/ok');
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
});
