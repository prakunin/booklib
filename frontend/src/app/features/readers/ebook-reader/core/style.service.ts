import {DOCUMENT} from '@angular/common';
import {inject, Injectable} from '@angular/core';
import {ReaderState} from '../state/reader-state.service';
import {EpubCustomFontService} from '../features/fonts/custom-font.service';
import {ACADEMY_BOOK_FONT, PT_SERIF_FONT} from '../state/built-in-fonts.constant';

interface ReaderRenderer {
  setAttribute(name: string, value: string | number): void;
  removeAttribute(name: string): void;
  setStyles?(css: string): void;
}

@Injectable({
  providedIn: 'root'
})
export class ReaderStyleService {
  private epubCustomFontService = inject(EpubCustomFontService);
  private document = inject(DOCUMENT);

  generateCSS(state: ReaderState): string {
    const {lineHeight, justify, hyphenate, fontSize, theme, fontFamily} = state;
    const adjustedBg = this.getAdjustedBackgroundColor(state);
    const documentBg = (state.backgroundTransparency ?? 0) > 0 ? 'transparent' : adjustedBg;
    const shouldOverrideLightBackground = (theme.bg || theme.light.bg) !== '#ffffff'
      || (state.backgroundSaturation ?? 100) !== 100
      || (state.backgroundTransparency ?? 0) !== 0;
    const userStylesheet = '';
    const overrideFont = false;
    const mediaActiveClass = 'media-active';

    let fontFaceRule = '';
    let actualFontFamily = null;

    if (fontFamily !== null) {
      const customFontId = this.parseCustomFontId(fontFamily);
      if (customFontId !== null) {
        const customFont = this.epubCustomFontService.getCustomFontById(customFontId);
        const blobUrl = this.epubCustomFontService.getBlobUrl(customFontId);
        if (customFont && blobUrl) {
          const sanitizedFontName = this.epubCustomFontService.sanitizeFontName(customFont.fontName);
          actualFontFamily = `"${sanitizedFontName}", sans-serif`;
          fontFaceRule = `@font-face {
              font-family: "${sanitizedFontName}";
              src: url("${blobUrl}") format("truetype");
              font-weight: normal;
              font-style: normal;
              font-display: block;
          }`;
        }
      } else if (fontFamily === ACADEMY_BOOK_FONT.family) {
        const fontUrl = new URL(ACADEMY_BOOK_FONT.assetPath, this.document.baseURI).href;
        actualFontFamily = `"${ACADEMY_BOOK_FONT.family}", ${ACADEMY_BOOK_FONT.fallback}`;
        fontFaceRule = `@font-face {
              font-family: "${ACADEMY_BOOK_FONT.family}";
              src: url("${fontUrl}") format("opentype");
              font-weight: normal;
              font-style: normal;
              font-display: block;
              size-adjust: ${ACADEMY_BOOK_FONT.sizeAdjust};
          }`;
      } else if (fontFamily === PT_SERIF_FONT.family) {
        actualFontFamily = `"${PT_SERIF_FONT.family}", ${PT_SERIF_FONT.fallback}`;
        fontFaceRule = PT_SERIF_FONT.faces.map(face => {
          const fontUrl = new URL(face.assetPath, this.document.baseURI).href;
          return `@font-face {
              font-family: "${PT_SERIF_FONT.family}";
              src: url("${fontUrl}") format("woff2");
              font-weight: ${face.weight};
              font-style: ${face.style};
              font-display: block;
          }`;
        }).join('\n');
      } else {
        actualFontFamily = fontFamily;
      }
    }

    const fontFamilyRule = actualFontFamily ? `
        body {
            font-family: ${actualFontFamily} !important;
        }
        body * {
            font-family: inherit !important;
        }` : '';

    return `
      ${fontFaceRule}
      @namespace epub "http://www.idpf.org/2007/ops";
      @media print {
          html {
              column-width: auto !important;
              height: auto !important;
              width: auto !important;
          }
      }
      @media screen {
          html {
              color-scheme: light dark;
              color: ${theme.fg || theme.light.fg};
              font-size: ${fontSize}px;
          }${fontFamilyRule}
          a:any-link {
              color: ${theme.link || theme.light.link};
              text-decoration-color: light-dark(
                  color-mix(in srgb, currentColor 20%, transparent),
                  color-mix(in srgb, currentColor 40%, transparent));
              text-underline-offset: .1em;
          }
          a:any-link:hover {
              text-decoration-color: unset;
          }
          @media (prefers-color-scheme: dark) {
              html {
                  color: ${theme.fg || theme.dark.fg};
              }
              a:any-link {
                  color: ${theme.link || theme.dark.link};
              }
          }
          aside[epub|type~="footnote"] {
              display: none;
          }
      }
      html {
          line-height: ${lineHeight};
          hanging-punctuation: allow-end last;
          orphans: 2;
          widows: 2;
      }
      [align="left"] { text-align: left; }
      [align="right"] { text-align: right; }
      [align="center"] { text-align: center; }
      [align="justify"] { text-align: justify; }
      :is(hgroup, header) p {
          text-align: unset;
          hyphens: unset;
      }
      h1, h2, h3, h4, h5, h6, hgroup, th {
          text-wrap: balance;
      }
      pre {
          white-space: pre-wrap !important;
          tab-size: 2;
      }
      @media screen and (prefers-color-scheme: light) {
          ${shouldOverrideLightBackground ? `
          html, body {
              color: ${theme.fg || theme.light.fg} !important;
              background: ${documentBg} !important;
          }
          body * {
              color: inherit !important;
              border-color: currentColor !important;
              background-color: ${documentBg} !important;
          }
          a:any-link {
              color: ${theme.link || theme.light.link} !important;
          }
          svg, img {
              background-color: transparent !important;
              mix-blend-mode: multiply;
          }
          .${mediaActiveClass}, .${mediaActiveClass} * {
              color: ${theme.fg || theme.light.fg} !important;
              background: color-mix(in hsl, ${theme.fg || theme.light.fg}, #fff 50%) !important;
              background: color-mix(in hsl, ${theme.fg || theme.light.fg}, ${adjustedBg} 85%) !important;
          }` : ''}
      }
      @media screen and (prefers-color-scheme: dark) {

          html, body {
              color: ${theme.fg || theme.dark.fg} !important;
              background: ${documentBg} !important;
          }
          body * {
              color: inherit !important;
              border-color: currentColor !important;
              background-color: ${documentBg} !important;
          }
          a:any-link {
              color: ${theme.link || theme.dark.link} !important;
          }
          .${mediaActiveClass}, .${mediaActiveClass} * {
              color: ${theme.fg || theme.dark.fg} !important;
              background: color-mix(in hsl, ${theme.fg || theme.dark.fg}, #000 50%) !important;
              background: color-mix(in hsl, ${theme.fg || theme.dark.fg}, ${adjustedBg} 75%) !important;
          }
      }
      p, li, blockquote, dd {
          line-height: ${lineHeight};
          text-align: ${justify ? 'justify' : 'start'} !important;
          hyphens: ${hyphenate ? 'auto' : 'none'};
      }
      ${overrideFont ? '' : ''}
      ${userStylesheet}
      ::selection {
          background-color: rgba(128, 128, 128, 0.3);
      }
      ::-moz-selection {
          background-color: rgba(128, 128, 128, 0.3);
      }
    `;
  }

  getAdjustedBackgroundColor(state: ReaderState): string {
    const baseColor = state.theme.bg || (state.isDark ? state.theme.dark.bg : state.theme.light.bg);
    const rgb = this.hexToRgb(baseColor);
    if (!rgb) return baseColor;

    const saturation = Math.max(0, Math.min(150, state.backgroundSaturation ?? 100)) / 100;
    const transparency = Math.max(0, Math.min(80, state.backgroundTransparency ?? 0)) / 100;
    const [h, s, l] = this.rgbToHsl(rgb.r, rgb.g, rgb.b);
    const adjusted = this.hslToRgb(h, Math.max(0, Math.min(1, s * saturation)), l);
    const alpha = Math.max(0.2, Math.min(1, 1 - transparency));

    return `rgba(${adjusted.r}, ${adjusted.g}, ${adjusted.b}, ${Number(alpha.toFixed(2))})`;
  }

  private hexToRgb(color: string): { r: number; g: number; b: number } | null {
    const hex = color.trim().replace(/^#/, '');
    const normalized = hex.length === 3
      ? hex.split('').map(char => char + char).join('')
      : hex;
    if (!/^[0-9a-f]{6}$/i.test(normalized)) return null;

    return {
      r: parseInt(normalized.slice(0, 2), 16),
      g: parseInt(normalized.slice(2, 4), 16),
      b: parseInt(normalized.slice(4, 6), 16),
    };
  }

  private rgbToHsl(r: number, g: number, b: number): [number, number, number] {
    r /= 255;
    g /= 255;
    b /= 255;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    let h = 0;
    let s = 0;
    const l = (max + min) / 2;

    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r:
          h = (g - b) / d + (g < b ? 6 : 0);
          break;
        case g:
          h = (b - r) / d + 2;
          break;
        default:
          h = (r - g) / d + 4;
          break;
      }
      h /= 6;
    }

    return [h, s, l];
  }

  private hslToRgb(h: number, s: number, l: number): { r: number; g: number; b: number } {
    if (s === 0) {
      const value = Math.round(l * 255);
      return {r: value, g: value, b: value};
    }

    const hue2rgb = (p: number, q: number, t: number): number => {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    };

    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;

    return {
      r: Math.round(hue2rgb(p, q, h + 1 / 3) * 255),
      g: Math.round(hue2rgb(p, q, h) * 255),
      b: Math.round(hue2rgb(p, q, h - 1 / 3) * 255),
    };
  }

  private parseCustomFontId(fontFamily: string): number | null {
    if (fontFamily.startsWith('custom:')) {
      const id = parseInt(fontFamily.substring(7), 10);
      return !isNaN(id) ? id : null;
    }

    const id = parseInt(fontFamily, 10);
    return !isNaN(id) && id.toString() === fontFamily ? id : null;
  }

  applyStylesToRenderer(renderer: ReaderRenderer | null | undefined, state: ReaderState): void {
    if (!renderer) return;

    renderer.setAttribute('max-column-count', state.maxColumnCount);
    const isFullWidth = state.flow === 'paginated' && state.pageMargin === 0;
    renderer.setAttribute('gap', isFullWidth ? '0%' : `${state.gap * 100}%`);
    renderer.setAttribute('max-inline-size', this.getMaxInlineSize(state, isFullWidth));
    if (state.flow === 'paginated') {
      renderer.setAttribute('max-block-size', `${state.maxBlockSize}px`);
    } else {
      renderer.removeAttribute('max-block-size');
    }
    if (typeof renderer.setStyles === 'function') {
      const css = this.generateCSS(state);
      renderer.setStyles(css);
    }

    renderer.setAttribute('margin', `${state.pageMargin}px`);
  }

  private getMaxInlineSize(state: ReaderState, isFullWidth: boolean): string {
    if (isFullWidth) {
      return '10000px';
    }

    if (state.flow === 'scrolled' || state.flow === 'continuous') {
      const viewportWidth = globalThis.innerWidth || this.document.documentElement.clientWidth || state.maxInlineSize;
      return `${Math.max(320, viewportWidth - state.pageMargin * 2)}px`;
    }

    return `${state.maxInlineSize}px`;
  }
}
