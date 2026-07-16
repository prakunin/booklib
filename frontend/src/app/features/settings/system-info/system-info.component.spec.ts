import {TestBed} from '@angular/core/testing';
import {describe, expect, it} from 'vitest';
import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {createQueryClientHarness} from '../../../core/testing/query-testing';
import {SystemInfoComponent} from './system-info.component';

describe('SystemInfoComponent', () => {
  it('renders a DOWN database without hiding the rest of the snapshot', async () => {
    const harness = createQueryClientHarness();

    await TestBed.configureTestingModule({
      imports: [SystemInfoComponent, getTranslocoModule()],
      providers: harness.providers,
    }).compileComponents();

    const fixture = TestBed.createComponent(SystemInfoComponent);
    fixture.componentRef.setInput('snapshot', {
      application: {version: 'v1.2.3', springBootVersion: '4.0.0'},
      runtime: {
        javaVersion: '25', javaVendor: 'Eclipse Adoptium', jvmUptimeMillis: 1000,
        availableProcessors: 8, heapUsedBytes: 100, heapMaxBytes: 200,
      },
      os: {name: 'Linux', version: '6.17.0', arch: 'amd64'},
      database: {vendor: null, version: null, status: 'DOWN'},
      storage: {diskType: 'LOCAL'},
      filesystems: [],
      libraryPaths: [{path: '/books/gone', status: 'MISSING'}],
      tools: {ffprobeVersion: null, kepubifyVersion: null},
    });
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('DOWN');
    expect(text).toContain('v1.2.3');
    expect(text).toContain('/books/gone');
  });
});
