import {ComponentFixture, TestBed} from '@angular/core/testing';
import {signal, WritableSignal} from '@angular/core';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {getTranslocoModule} from '../../../../core/testing/transloco-testing';
import {EpubCustomFontService} from '../features/fonts/custom-font.service';
import {ReaderState, ReaderStateService} from '../state/reader-state.service';
import {themes} from '../state/themes.constant';
import {ReaderViewManagerService} from '../core/view-manager.service';
import {ReaderSettingsDialogComponent} from './settings-dialog.component';

describe('ReaderSettingsDialogComponent', () => {
  let fixture: ComponentFixture<ReaderSettingsDialogComponent>;
  let component: ReaderSettingsDialogComponent;
  let stateSignal: WritableSignal<ReaderState>;
  let stateService: Record<string, unknown>;
  let viewManager: {setFlow: ReturnType<typeof vi.fn>};

  const initialState: ReaderState = {
    lineHeight: 1.5,
    justify: true,
    hyphenate: true,
    maxColumnCount: 2,
    gap: 0.05,
    fontSize: 16,
    theme: {...themes[0], fg: themes[0].dark.fg, bg: themes[0].dark.bg, link: themes[0].dark.link},
    maxInlineSize: 720,
    maxBlockSize: 1440,
    pageMargin: 40,
    fontFamily: null,
    isDark: true,
    flow: 'paginated',
    backgroundSaturation: 100,
    backgroundTransparency: 0,
  };

  beforeEach(async () => {
    stateSignal = signal(structuredClone(initialState));
    stateService = {
      state: stateSignal.asReadonly(),
      themes,
      fonts: vi.fn(() => []),
      usesGlobalSettings: vi.fn(() => false),
      getStateSnapshot: vi.fn(() => structuredClone(stateSignal())),
      restoreState: vi.fn((snapshot: ReaderState) => stateSignal.set(structuredClone(snapshot))),
      persistSettings: vi.fn(),
      setGlobalSettings: vi.fn(),
      setFontFamily: vi.fn(),
      updateFontSize: vi.fn(),
      updateLineHeight: vi.fn(),
      updateMaxColumnCount: vi.fn(),
      updateGap: vi.fn(),
      setBackgroundSaturation: vi.fn(),
      setBackgroundTransparency: vi.fn(),
      toggleJustify: vi.fn(),
      toggleHyphenate: vi.fn(),
      updateMaxInlineSize: vi.fn(),
      updateMaxBlockSize: vi.fn(),
      updatePageMargin: vi.fn(),
      toggleFullWidth: vi.fn(),
      toggleDarkMode: vi.fn(),
      setThemeByName: vi.fn(),
      setFlow: vi.fn(),
    };
    viewManager = {setFlow: vi.fn()};

    await TestBed.configureTestingModule({
      imports: [ReaderSettingsDialogComponent, getTranslocoModule()],
      providers: [{
        provide: EpubCustomFontService,
        useValue: {
          injectCustomFontsStylesheet: vi.fn(),
          getFontFamilyForPreview: vi.fn((value: string) => value),
        }
      }]
    }).compileComponents();

    fixture = TestBed.createComponent(ReaderSettingsDialogComponent);
    component = fixture.componentInstance;
    component.stateService = stateService as unknown as ReaderStateService;
    component.viewManager = viewManager as unknown as ReaderViewManagerService;
    component.bookId = 12;
    localStorage.setItem('selectedAnnotationColor', '#FFFF00');
    fixture.detectChanges();
  });

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('restores the initial preview and closes when cancelled', () => {
    const closed = vi.fn();
    component.closed.subscribe(closed);
    stateSignal.set({...stateSignal(), fontSize: 24, flow: 'scrolled'});
    component.setAnnotationColor('#87CEEB');

    component.cancel();

    expect(stateService['restoreState']).toHaveBeenCalledWith(initialState);
    expect(viewManager.setFlow).toHaveBeenCalledWith('paginated');
    expect(localStorage.getItem('selectedAnnotationColor')).toBe('#FFFF00');
    expect(closed).toHaveBeenCalledOnce();
  });

  it('persists the preview and annotation color only when saved', () => {
    const closed = vi.fn();
    component.closed.subscribe(closed);
    component.setAnnotationColor('#87CEEB');

    component.save();

    expect(stateService['persistSettings']).toHaveBeenCalledWith(12);
    expect(localStorage.getItem('selectedAnnotationColor')).toBe('#87CEEB');
    expect(closed).toHaveBeenCalledOnce();
  });

  it('defers the global-setting change until save', () => {
    component.toggleGlobalSettings();

    expect(stateService['setGlobalSettings']).not.toHaveBeenCalled();

    component.save();

    expect(stateService['setGlobalSettings']).toHaveBeenCalledWith(true, 12);
    expect(stateService['persistSettings']).not.toHaveBeenCalled();
  });
});
