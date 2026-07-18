import {TestBed} from '@angular/core/testing';
import {of, firstValueFrom} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {BookService} from '../../../book/service/book.service';
import {UserService} from '../../../settings/user-management/user.service';
import {EpubCustomFontService} from '../features/fonts/custom-font.service';
import {ReaderStateService} from './reader-state.service';
import {themes} from './themes.constant';

describe('ReaderStateService', () => {
  const customFonts = [
    {id: 2, fontName: 'Fancy Serif.otf', originalFileName: 'Fancy Serif.otf', format: 'OTF', fileSize: 1024, uploadedAt: '2026-03-26T00:00:00Z'},
  ];

  const epubCustomFontService = {
    getCustomFonts: vi.fn(() => customFonts),
  };

  const bookService = {
    getBookSetting: vi.fn(),
    updateViewerSetting: vi.fn(() => of(void 0)),
  };

  const userService = {
    getMyself: vi.fn(),
    updateUserSetting: vi.fn(),
  };

  let service: ReaderStateService;

  beforeEach(() => {
    bookService.getBookSetting.mockReset();
    bookService.updateViewerSetting.mockReset();
    bookService.updateViewerSetting.mockReturnValue(of(void 0));
    userService.getMyself.mockReset();
    userService.updateUserSetting.mockReset();
    epubCustomFontService.getCustomFonts.mockReset();
    epubCustomFontService.getCustomFonts.mockReturnValue(customFonts);

    TestBed.configureTestingModule({
      providers: [
        ReaderStateService,
        {provide: BookService, useValue: bookService},
        {provide: UserService, useValue: userService},
        {provide: EpubCustomFontService, useValue: epubCustomFontService},
      ]
    });

    service = TestBed.inject(ReaderStateService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('loads custom fonts into the reader font list on construction and refresh', () => {
    expect(service.fonts()).toContainEqual({name: 'Academy Book', value: 'Academy Book'});
    expect(service.fonts()).toContainEqual({name: 'Fancy Serif', value: 'custom:2'});

    epubCustomFontService.getCustomFonts.mockReturnValue([
      {id: 8, fontName: 'Display Sans.woff2', originalFileName: 'Display Sans.woff2', format: 'WOFF2', fileSize: 2048, uploadedAt: '2026-03-26T00:00:00Z'},
    ]);

    service.refreshCustomFonts();

    expect(service.fonts()).toContainEqual({name: 'Display Sans', value: 'custom:8'});
    expect(service.fonts()).not.toContainEqual({name: 'Fancy Serif', value: 'custom:2'});
  });

  it('applies global reader settings from the user profile', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 7,
      userSettings: {
        perBookSetting: {epub: 'Global'},
        ebookReaderSetting: {
          fontSize: 18,
          lineHeight: 1.75,
          fontFamily: '5',
          gap: 0.75,
          hyphenate: false,
          justify: false,
          maxColumnCount: 4,
          maxInlineSize: 900,
          maxBlockSize: 1800,
          pageMargin: 0,
          isDark: false,
          flow: 'scrolled',
          theme: 'gray',
          backgroundSaturation: 135,
          backgroundTransparency: 25,
        }
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({ebookSettings: {fontSize: 22}}));

    await firstValueFrom(service.initializeState(11, 22));

    const grayTheme = themes.find(theme => theme.name === 'gray');

    expect(bookService.getBookSetting).toHaveBeenCalledWith(11, 22);
    expect(service.state().fontSize).toBe(18);
    expect(service.state().lineHeight).toBe(1.75);
    expect(service.state().fontFamily).toBe('custom:5');
    expect(service.state().gap).toBe(0.5);
    expect(service.state().hyphenate).toBe(false);
    expect(service.state().justify).toBe(false);
    expect(service.state().maxColumnCount).toBe(4);
    expect(service.state().maxInlineSize).toBe(900);
    expect(service.state().maxBlockSize).toBe(1800);
    expect(service.state().pageMargin).toBe(0);
    expect(service.state().isDark).toBe(false);
    expect(service.state().flow).toBe('scrolled');
    expect(service.state().theme.name).toBe('gray');
    expect(service.state().theme.fg).toBe(grayTheme?.light.fg);
    expect(service.state().backgroundSaturation).toBe(135);
    expect(service.state().backgroundTransparency).toBe(25);
  });

  it('applies per-book settings and legacy custom font ids', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 8,
      userSettings: {
        perBookSetting: {epub: 'PerBook'},
        ebookReaderSetting: {
          fontSize: 16,
          lineHeight: 1.5,
          gap: 0.05,
          hyphenate: true,
          justify: true,
          maxColumnCount: 2,
          maxInlineSize: 720,
          maxBlockSize: 1440,
          isDark: true,
          flow: 'paginated',
          theme: 'default',
        }
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({
      ebookSettings: {
        customFontId: 12,
        fontSize: 20,
        lineHeight: 2,
        gap: 0.3,
        hyphenate: false,
        justify: false,
        maxColumnCount: 3,
        maxInlineSize: 800,
        maxBlockSize: 1500,
        pageMargin: 12,
        isDark: true,
        flow: 'paginated',
        theme: 'ember',
      }
    }));

    await firstValueFrom(service.initializeState(8, 9));

    const emberTheme = themes.find(theme => theme.name === 'ember');

    expect(service.state().fontFamily).toBe('custom:12');
    expect(service.state().fontSize).toBe(20);
    expect(service.state().lineHeight).toBe(2);
    expect(service.state().gap).toBe(0.3);
    expect(service.state().pageMargin).toBe(12);
    expect(service.state().theme.name).toBe('ember');
    expect(service.state().theme.fg).toBe(emberTheme?.dark.fg);
  });

  it('persists reader changes to the user profile in global mode', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 7,
      userSettings: {
        perBookSetting: {epub: 'Global'},
        ebookReaderSetting: {fontSize: 16, fontFamily: 'serif'}
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({}));
    await firstValueFrom(service.initializeState(11, 22));

    service.setFontFamily('Academy Book');
    service.persistSettings(11);

    expect(userService.updateUserSetting).toHaveBeenCalledWith(7, 'ebookReaderSetting', expect.objectContaining({
      fontFamily: 'Academy Book',
      fontSize: 16,
    }));
    expect(bookService.updateViewerSetting).not.toHaveBeenCalled();
  });

  it('persists reader changes for the current book in individual mode', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 8,
      userSettings: {
        perBookSetting: {epub: 'Individual'},
        ebookReaderSetting: {fontSize: 16}
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({ebookSettings: {fontSize: 20}}));
    await firstValueFrom(service.initializeState(11, 22));

    service.persistSettings(11);

    expect(bookService.updateViewerSetting).toHaveBeenCalledWith({
      ebookSettings: expect.objectContaining({fontSize: 20})
    }, 11);
    expect(userService.updateUserSetting).not.toHaveBeenCalled();
  });

  it('makes the current reader appearance global for all books', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 9,
      userSettings: {
        perBookSetting: {pdf: 'Individual', epub: 'Individual', cbx: 'Individual'},
        ebookReaderSetting: {fontSize: 16}
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({ebookSettings: {fontSize: 20}}));
    await firstValueFrom(service.initializeState(11, 22));
    service.setFontFamily('Academy Book');

    service.setGlobalSettings(true, 11);

    expect(service.usesGlobalSettings()).toBe(true);
    expect(userService.updateUserSetting).toHaveBeenCalledWith(9, 'ebookReaderSetting', expect.objectContaining({
      fontFamily: 'Academy Book',
      fontSize: 20,
    }));
    expect(userService.updateUserSetting).toHaveBeenCalledWith(9, 'perBookSetting', {
      pdf: 'Individual',
      epub: 'Global',
      cbx: 'Individual',
    });
    expect(bookService.updateViewerSetting).not.toHaveBeenCalled();
  });

  it('returns to per-book settings without losing the current book appearance', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 10,
      userSettings: {
        perBookSetting: {pdf: 'Individual', epub: 'Global', cbx: 'Individual'},
        ebookReaderSetting: {fontSize: 18, fontFamily: 'Academy Book'}
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({}));
    await firstValueFrom(service.initializeState(11, 22));

    service.setGlobalSettings(false, 11);

    expect(service.usesGlobalSettings()).toBe(false);
    expect(bookService.updateViewerSetting).toHaveBeenCalledWith({
      ebookSettings: expect.objectContaining({fontFamily: 'Academy Book', fontSize: 18})
    }, 11);
    expect(userService.updateUserSetting).toHaveBeenCalledWith(10, 'perBookSetting', {
      pdf: 'Individual',
      epub: 'Individual',
      cbx: 'Individual',
    });
  });

  it('supports the small state mutators and clamps values', () => {
    service.updateLineHeight(-10);
    service.updateMaxColumnCount(20);
    service.updateGap(1);
    service.toggleJustify();
    service.toggleHyphenate();
    service.updateFontSize(20);
    service.updateMaxInlineSize(-500);
    service.updateMaxBlockSize(2000);
    service.toggleFullWidth();
    service.setThemeByName('sepia');
    service.setFontFamily('12');
    service.toggleDarkMode();
    service.setFlow('scrolled');
    service.setBackgroundSaturation(200);
    service.setBackgroundTransparency(100);

    expect(service.state().lineHeight).toBe(0.8);
    expect(service.state().maxColumnCount).toBe(10);
    expect(service.state().gap).toBe(0.5);
    expect(service.state().justify).toBe(false);
    expect(service.state().hyphenate).toBe(false);
    expect(service.state().fontSize).toBe(36);
    expect(service.state().maxInlineSize).toBe(400);
    expect(service.state().maxBlockSize).toBe(2400);
    expect(service.state().pageMargin).toBe(0);
    expect(service.state().theme.name).toBe('sepia');
    expect(service.state().fontFamily).toBe('custom:12');
    expect(service.state().isDark).toBe(false);
    expect(service.state().flow).toBe('scrolled');
    expect(service.state().backgroundSaturation).toBe(150);
    expect(service.state().backgroundTransparency).toBe(80);
  });

  it('normalizes legacy continuous flow to scrolled', async () => {
    userService.getMyself.mockReturnValue(of({
      id: 7,
      userSettings: {
        perBookSetting: {epub: 'Global'},
        ebookReaderSetting: {flow: 'continuous'}
      }
    }));
    bookService.getBookSetting.mockReturnValue(of({}));

    await firstValueFrom(service.initializeState(11, 22));
    service.persistSettings(11);

    expect(service.state().flow).toBe('scrolled');
    expect(userService.updateUserSetting).toHaveBeenCalledWith(7, 'ebookReaderSetting', expect.objectContaining({
      flow: 'scrolled',
    }));
  });

  it('clamps direct font size and page margin updates', () => {
    service.setFontSize(100);
    service.updatePageMargin(500);

    expect(service.state().fontSize).toBe(40);
    expect(service.state().pageMargin).toBe(160);

    service.setFontSize(2);
    service.updatePageMargin(-500);

    expect(service.state().fontSize).toBe(10);
    expect(service.state().pageMargin).toBe(0);
  });

  it('ignores unknown theme names', () => {
    const initialTheme = service.state().theme;

    service.setThemeByName('does-not-exist');

    expect(service.state().theme).toEqual(initialTheme);
  });
});
