import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {themes} from '../state/themes.constant';
import {ReaderState} from '../state/reader-state.service';
import {EpubCustomFontService} from '../features/fonts/custom-font.service';
import {ReaderStyleService} from './style.service';

function makeState(over: Partial<ReaderState>): ReaderState {
  return {
    lineHeight: 1.5, justify: true, hyphenate: true, maxColumnCount: 2, gap: 0.05,
    fontSize: 16, theme: themes[0], maxInlineSize: 720, maxBlockSize: 1440, pageMargin: 40,
    fontFamily: null, isDark: true, flow: 'paginated', backgroundSaturation: 100,
    backgroundTransparency: 0, ...over,
  };
}

describe('ReaderStyleService', () => {
  const epubCustomFontService = {
    getCustomFontById: vi.fn(),
    getBlobUrl: vi.fn(),
    sanitizeFontName: vi.fn(),
  };

  let service: ReaderStyleService;

  beforeEach(() => {
    epubCustomFontService.getCustomFontById.mockReset();
    epubCustomFontService.getBlobUrl.mockReset();
    epubCustomFontService.sanitizeFontName.mockReset();
    epubCustomFontService.sanitizeFontName.mockImplementation((fontName: string) => fontName.replace(/\W+/g, '-'));

    TestBed.configureTestingModule({
      providers: [
        ReaderStyleService,
        {provide: EpubCustomFontService, useValue: epubCustomFontService},
      ]
    });

    service = TestBed.inject(ReaderStyleService);
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('generates custom font css when a cached custom font is selected', () => {
    epubCustomFontService.getCustomFontById.mockReturnValue({fontName: 'Fancy Serif.otf'});
    epubCustomFontService.getBlobUrl.mockReturnValue('blob:fancy-serif');

    const css = service.generateCSS({
      lineHeight: 1.6,
      justify: false,
      hyphenate: true,
      maxColumnCount: 2,
      gap: 0.05,
      fontSize: 18,
      theme: {...themes[0], fg: themes[0].dark.fg, bg: themes[0].dark.bg, link: themes[0].dark.link},
      maxInlineSize: 720,
      maxBlockSize: 1440,
      pageMargin: 40,
      fontFamily: 'custom:7',
      isDark: true,
      flow: 'paginated',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    });

    expect(epubCustomFontService.getCustomFontById).toHaveBeenCalledWith(7);
    expect(epubCustomFontService.getBlobUrl).toHaveBeenCalledWith(7);
    expect(css).toContain('@font-face');
    expect(css).toContain('font-family: "Fancy-Serif-otf", sans-serif !important;');
    expect(css).toContain('font-size: 18px;');
    expect(css).toContain('text-align: start !important;');
    expect(css).toContain('hyphens: auto;');
  });

  it('falls back to plain font families when the font value is not a cached custom font', () => {
    const css = service.generateCSS({
      lineHeight: 1.4,
      justify: true,
      hyphenate: false,
      maxColumnCount: 2,
      gap: 0.05,
      fontSize: 16,
      theme: {...themes[1], fg: themes[1].light.fg, bg: themes[1].light.bg, link: themes[1].light.link},
      maxInlineSize: 720,
      maxBlockSize: 1440,
      pageMargin: 40,
      fontFamily: 'serif',
      isDark: false,
      flow: 'scrolled',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    });

    expect(epubCustomFontService.getCustomFontById).not.toHaveBeenCalled();
    expect(css).not.toContain('@font-face');
    expect(css).toContain('font-family: serif !important;');
    expect(css).toContain('text-align: justify !important;');
    expect(css).toContain('hyphens: none;');
  });

  it('embeds the built-in Academy Book font in the reader document', () => {
    const css = service.generateCSS({
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
      fontFamily: 'Academy Book',
      isDark: true,
      flow: 'paginated',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    });

    expect(css).toContain('@font-face');
    expect(css).toContain('font-family: "Academy Book";');
    expect(css).toContain('/assets/fonts/AcademyBook-Regular.otf');
    expect(css).toContain('size-adjust: 135%;');
    expect(css).toContain('font-family: "Academy Book", serif !important;');
  });

  it('adjusts reader background saturation and transparency', () => {
    expect(service.getAdjustedBackgroundColor({
      lineHeight: 1.5,
      justify: true,
      hyphenate: true,
      maxColumnCount: 2,
      gap: 0.05,
      fontSize: 16,
      theme: {...themes[8], fg: themes[8].light.fg, bg: themes[8].light.bg, link: themes[8].light.link},
      maxInlineSize: 720,
      maxBlockSize: 1440,
      pageMargin: 40,
      fontFamily: null,
      isDark: false,
      flow: 'continuous',
      backgroundSaturation: 0,
      backgroundTransparency: 40,
    })).toBe('rgba(225, 225, 225, 0.6)');
  });

  it('resolves the foreground from the light/dark sub-object when theme.fg is empty', () => {
    // an unresolved theme (no top-level fg/bg) must still yield a real colour so
    // the footnote popover never binds an empty custom property
    expect(service.getForegroundColor(makeState({theme: themes[0], isDark: false})))
      .toBe(themes[0].light.fg);
    expect(service.getForegroundColor(makeState({theme: themes[0], isDark: true})))
      .toBe(themes[0].dark.fg);
    expect(service.getBaseBackgroundColor(makeState({theme: themes[0], isDark: false})))
      .toBe(themes[0].light.bg);
    expect(service.getBaseBackgroundColor(makeState({theme: themes[0], isDark: true})))
      .toBe(themes[0].dark.bg);
  });

  it('keeps foreground and background on the active mode when legacy top-level colours are incomplete', () => {
    const theme = {...themes[2], fg: themes[2].dark.fg, bg: undefined};

    expect(service.getForegroundColor(makeState({theme, isDark: false}))).toBe(themes[2].light.fg);
    expect(service.getBaseBackgroundColor(makeState({theme, isDark: false}))).toBe(themes[2].light.bg);
  });

  it('applies renderer attributes and reader margins only when a renderer exists', () => {
    const renderer = {
      setAttribute: vi.fn(),
      removeAttribute: vi.fn(),
      setStyles: vi.fn(),
    };

    service.applyStylesToRenderer(renderer, {
      lineHeight: 1.5,
      justify: true,
      hyphenate: true,
      maxColumnCount: 3,
      gap: 0.2,
      fontSize: 20,
      theme: {...themes[0], fg: themes[0].dark.fg, bg: themes[0].dark.bg, link: themes[0].dark.link},
      maxInlineSize: 800,
      maxBlockSize: 1500,
      pageMargin: 40,
      fontFamily: null,
      isDark: true,
      flow: 'paginated',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    });

    expect(renderer.setAttribute).toHaveBeenCalledWith('max-column-count', 3);
    expect(renderer.setAttribute).toHaveBeenCalledWith('gap', '20%');
    expect(renderer.setAttribute).toHaveBeenCalledWith('max-inline-size', '800px');
    expect(renderer.setAttribute).toHaveBeenCalledWith('max-block-size', '1500px');
    expect(renderer.setAttribute).toHaveBeenCalledWith('margin', '40px');
    expect(renderer.setStyles).toHaveBeenCalledOnce();

    service.applyStylesToRenderer(renderer, {
      lineHeight: 1.5,
      justify: true,
      hyphenate: true,
      maxColumnCount: 3,
      gap: 0.2,
      fontSize: 20,
      theme: {...themes[0], fg: themes[0].dark.fg, bg: themes[0].dark.bg, link: themes[0].dark.link},
      maxInlineSize: 800,
      maxBlockSize: 1500,
      pageMargin: 40,
      fontFamily: null,
      isDark: true,
      flow: 'scrolled',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    });

    expect(renderer.setAttribute).toHaveBeenCalledWith('max-inline-size', `${Math.max(320, globalThis.innerWidth - 80)}px`);
    expect(renderer.removeAttribute).toHaveBeenCalledWith('max-block-size');
    expect(renderer.setAttribute).toHaveBeenCalledWith('margin', '40px');
    expect(() => service.applyStylesToRenderer(null, {
      lineHeight: 1.5,
      justify: true,
      hyphenate: true,
      maxColumnCount: 3,
      gap: 0.2,
      fontSize: 20,
      theme: {...themes[0], fg: themes[0].dark.fg, bg: themes[0].dark.bg, link: themes[0].dark.link},
      maxInlineSize: 800,
      maxBlockSize: 1500,
      pageMargin: 40,
      fontFamily: null,
      isDark: true,
      flow: 'scrolled',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    })).not.toThrow();
  });

  it('uses the complete viewport when full-width pages are enabled', () => {
    const renderer = {
      setAttribute: vi.fn(),
      removeAttribute: vi.fn(),
      setStyles: vi.fn(),
    };

    service.applyStylesToRenderer(renderer, {
      lineHeight: 1.5,
      justify: true,
      hyphenate: true,
      maxColumnCount: 2,
      gap: 0.05,
      fontSize: 18,
      theme: {...themes[0], fg: themes[0].dark.fg, bg: themes[0].dark.bg, link: themes[0].dark.link},
      maxInlineSize: 720,
      maxBlockSize: 1440,
      pageMargin: 0,
      fontFamily: null,
      isDark: true,
      flow: 'paginated',
      backgroundSaturation: 100,
      backgroundTransparency: 0,
    });

    expect(renderer.setAttribute).toHaveBeenCalledWith('gap', '0%');
    expect(renderer.setAttribute).toHaveBeenCalledWith('max-inline-size', '10000px');
    expect(renderer.setAttribute).toHaveBeenCalledWith('margin', '0px');
  });
});
