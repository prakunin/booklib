import {computed, inject, Injectable, signal} from '@angular/core';
import {forkJoin, Observable} from 'rxjs';
import {map, tap} from 'rxjs/operators';
import {Theme, themes} from './themes.constant';
import {BookService} from '../../../book/service/book.service';
import {EbookViewerSetting} from '../../../book/model/book.model';
import {PerBookSetting, UserService} from '../../../settings/user-management/user.service';
import {EpubCustomFontService} from '../features/fonts/custom-font.service';
import {ACADEMY_BOOK_FONT} from './built-in-fonts.constant';

export interface ReaderState {
  lineHeight: number;
  justify: boolean;
  hyphenate: boolean;
  maxColumnCount: number;
  gap: number;
  fontSize: number;
  theme: Theme;
  maxInlineSize: number;
  maxBlockSize: number;
  fontFamily: string | null;
  isDark: boolean;
  flow: 'paginated' | 'scrolled';
}

interface LegacyViewerSetting {
  customFontId?: string | number | null;
}

@Injectable({
  providedIn: 'root'
})
export class ReaderStateService {
  private bookService = inject(BookService);
  private userService = inject(UserService);
  private epubCustomFontService = inject(EpubCustomFontService);

  private readonly BASE_FONTS = [
    {name: 'Publisher\'s', value: null},
    {name: ACADEMY_BOOK_FONT.name, value: ACADEMY_BOOK_FONT.family},
    {name: 'Serif', value: 'serif'},
    {name: 'Sans-Serif', value: 'sans-serif'},
    {name: 'Monospace', value: 'monospace'},
    {name: 'Cursive', value: 'cursive'},
  ];

  private readonly defaultState: ReaderState = {
    lineHeight: 1.5,
    justify: true,
    hyphenate: true,
    maxColumnCount: 2,
    gap: 0.05,
    fontSize: 16,
    theme: {
      ...themes[0],
      fg: themes[0].dark.fg,
      bg: themes[0].dark.bg,
      link: themes[0].dark.link,
    },
    maxInlineSize: 720,
    maxBlockSize: 1440,
    fontFamily: null,
    isDark: true,
    flow: 'paginated',
  };

  private readonly _state = signal<ReaderState>(this.defaultState);
  private readonly _settingScope = signal<'Global' | 'Individual'>('Individual');
  private userId: number | null = null;
  private perBookSetting: PerBookSetting | null = null;
  readonly state = this._state.asReadonly();
  readonly usesGlobalSettings = computed(() => this._settingScope() === 'Global');

  readonly themes = themes;
  private readonly _fonts = signal<{ name: string; value: string | null }[]>(this.BASE_FONTS);
  readonly fonts = this._fonts.asReadonly();

  constructor() {
    this.loadCustomFontsIntoList();
  }

  private loadCustomFontsIntoList(): void {
    const customFonts = this.epubCustomFontService.getCustomFonts();
    const customFontOptions = customFonts.map(font => ({
      name: font.fontName.replace(/\.(ttf|otf|woff|woff2)$/i, ''),
      value: `custom:${font.id}`
    }));

    const updatedFonts = [
      ...this._fonts(),
      ...customFontOptions
    ];

    this._fonts.set(updatedFonts);
  }

  refreshCustomFonts(): void {
    this._fonts.set([...this.BASE_FONTS]);
    this.loadCustomFontsIntoList();
  }

  initializeState(bookId: number, bookFileId: number): Observable<void> {
    return forkJoin([
      this.userService.getMyself(),
      this.bookService.getBookSetting(bookId, bookFileId)
    ]).pipe(
      tap(([myself, bookSetting]) => {
        const settingScope = myself.userSettings.perBookSetting.epub;
        this._settingScope.set(settingScope === 'Global' ? 'Global' : 'Individual');
        this.userId = myself.id;
        this.perBookSetting = {...myself.userSettings.perBookSetting};
        const globalSettings = myself.userSettings.ebookReaderSetting;
        const individualSetting = bookSetting?.ebookSettings;
        const settings = settingScope === 'Global' ? globalSettings : (individualSetting || globalSettings);
        const newState: Partial<ReaderState> = {};
        if (settings.fontSize != null) newState.fontSize = settings.fontSize;
        if (settings.lineHeight != null) newState.lineHeight = settings.lineHeight;

        if (settings.fontFamily != null) {
          if (settings.fontFamily.startsWith('custom:')) {
            newState.fontFamily = settings.fontFamily;
          } else {
            const numericId = parseInt(settings.fontFamily, 10);
            if (!isNaN(numericId) && numericId.toString() === settings.fontFamily) {
              newState.fontFamily = `custom:${numericId}`;
            } else {
              newState.fontFamily = settings.fontFamily;
            }
          }
        } else {
          const legacySettings = settings as LegacyViewerSetting;
          if (legacySettings.customFontId != null) {
            newState.fontFamily = `custom:${legacySettings.customFontId}`;
          }
        }

        if (settings.gap != null) newState.gap = Math.min(settings.gap, 0.5);
        if (settings.hyphenate != null) newState.hyphenate = settings.hyphenate;
        if (settings.justify != null) newState.justify = settings.justify;
        if (settings.maxColumnCount != null) newState.maxColumnCount = settings.maxColumnCount;
        if (settings.maxInlineSize != null) newState.maxInlineSize = settings.maxInlineSize;
        if (settings.maxBlockSize != null) newState.maxBlockSize = settings.maxBlockSize;
        if (settings.isDark != null) newState.isDark = settings.isDark;
        if (settings.flow) newState.flow = settings.flow;
        if (settings.theme) {
          const theme = this.themes.find(t => t.name === settings.theme);
          if (theme) {
            newState.theme = {
              ...theme,
              fg: settings.isDark ? theme.dark.fg : theme.light.fg,
              bg: settings.isDark ? theme.dark.bg : theme.light.bg,
              link: settings.isDark ? theme.dark.link : theme.light.link,
            };
          }
        }
        if (Object.keys(newState).length > 0) {
          this.updateState(newState);
        }
      }),
      map(() => void 0)
    );
  }

  updateLineHeight(delta: number): void {
    const current = this._state().lineHeight;
    const newValue = Math.max(0.8, Math.min(3, current + delta));
    this.updateState({lineHeight: newValue});
  }

  updateMaxColumnCount(delta: number): void {
    const current = this._state().maxColumnCount;
    const newValue = Math.max(1, Math.min(10, current + delta));
    this.updateState({maxColumnCount: newValue});
  }

  updateGap(delta: number): void {
    const current = this._state().gap;
    const newValue = Math.max(0, Math.min(0.5, current + delta));
    this.updateState({gap: newValue});
  }

  toggleJustify(): void {
    this.updateState({justify: !this._state().justify});
  }

  toggleHyphenate(): void {
    this.updateState({hyphenate: !this._state().hyphenate});
  }

  updateFontSize(delta: number): void {
    const newFontSize = Math.max(10, Math.min(32, this._state().fontSize + delta));
    this.updateState({fontSize: newFontSize});
  }

  setTheme(theme: Theme): void {
    this.updateState({theme});
  }

  setFontFamily(font: string | null): void {
    if (font === null) {
      this.updateState({fontFamily: null});
    } else if (!font.includes(':') && !isNaN(parseInt(font, 10)) && parseInt(font, 10).toString() === font) {
      this.updateState({fontFamily: `custom:${font}`});
    } else {
      this.updateState({fontFamily: font});
    }
  }

  updateMaxInlineSize(delta: number): void {
    const newValue = Math.max(400, Math.min(1600, this._state().maxInlineSize + delta));
    this.updateState({maxInlineSize: newValue});
  }

  updateMaxBlockSize(delta: number): void {
    const newValue = Math.max(600, Math.min(2400, this._state().maxBlockSize + delta));
    this.updateState({maxBlockSize: newValue});
  }

  toggleDarkMode() {
    const currentState = this._state();
    const currentTheme = currentState.theme;
    const newIsDark = !currentState.isDark;
    const newTheme = {
      ...currentTheme,
      fg: newIsDark ? currentTheme.dark.fg : currentTheme.light.fg,
      bg: newIsDark ? currentTheme.dark.bg : currentTheme.light.bg,
      link: newIsDark ? currentTheme.dark.link : currentTheme.light.link,
    };
    this.updateState({theme: newTheme, isDark: newIsDark});
  }

  setThemeByName(themeName: string) {
    const theme = this.themes.find(t => t.name === themeName);
    if (theme) {
      const currentState = this._state();
      const newTheme = {
        ...theme,
        fg: currentState.isDark ? theme.dark.fg : theme.light.fg,
        bg: currentState.isDark ? theme.dark.bg : theme.light.bg,
        link: currentState.isDark ? theme.dark.link : theme.light.link,
      };
      this.setTheme(newTheme);
    }
  }

  setFlow(flow: 'paginated' | 'scrolled'): void {
    this.updateState({flow});
  }

  persistSettings(bookId: number): void {
    const setting = this.getViewerSetting();

    if (this._settingScope() === 'Global' && this.userId !== null) {
      this.userService.updateUserSetting(this.userId, 'ebookReaderSetting', setting);
      return;
    }

    this.bookService.updateViewerSetting({ebookSettings: setting}, bookId).subscribe();
  }

  setGlobalSettings(enabled: boolean, bookId: number): void {
    if (this.userId === null || this.perBookSetting === null) return;

    const scope = enabled ? 'Global' : 'Individual';
    this._settingScope.set(scope);
    this.perBookSetting = {...this.perBookSetting, epub: scope};

    if (enabled) {
      this.userService.updateUserSetting(this.userId, 'ebookReaderSetting', this.getViewerSetting());
    } else {
      this.bookService.updateViewerSetting({ebookSettings: this.getViewerSetting()}, bookId).subscribe();
    }
    this.userService.updateUserSetting(this.userId, 'perBookSetting', this.perBookSetting);
  }

  private getViewerSetting(): EbookViewerSetting {
    const state = this._state();
    return {
      lineHeight: state.lineHeight,
      justify: state.justify,
      hyphenate: state.hyphenate,
      maxColumnCount: state.maxColumnCount,
      gap: state.gap,
      fontSize: state.fontSize,
      theme: state.theme.name,
      maxInlineSize: state.maxInlineSize,
      maxBlockSize: state.maxBlockSize,
      fontFamily: state.fontFamily,
      isDark: state.isDark,
      flow: state.flow,
    };
  }

  private updateState(partial: Partial<ReaderState>): void {
    this._state.update(current => ({...current, ...partial}));
  }
}
