import {inject, Injectable, signal} from '@angular/core';
import {ReaderViewManagerService} from '../../core/view-manager.service';

const EPUB_OPS_NS = 'http://www.idpf.org/2007/ops';
const NOTE_TYPES = ['noteref', 'footnote', 'endnote', 'rearnote'];
const STRIPPED_TAGS = 'script, style, link, img, svg, video, audio, iframe, object, embed';
const POPUP_EDGE_MARGIN_PX = 16;
const POPUP_GAP_PX = 8;

export interface FootnoteState {
  visible: boolean;
  position: { x: number; y: number };
  showBelow: boolean;
  html: string;
  href: string;
}

@Injectable()
export class ReaderFootnoteService {
  private readonly viewManager = inject(ReaderViewManagerService);

  private readonly defaultState: FootnoteState = {
    visible: false,
    position: {x: 0, y: 0},
    showBelow: false,
    html: '',
    href: ''
  };

  private readonly _state = signal<FootnoteState>(this.defaultState);
  readonly state = this._state.asReadonly();

  register(): void {
    this.viewManager.setLinkHandler((anchor, href) => {
      if (!this.isFootnote(anchor, href)) return false;
      void this.open(anchor, href);
      return true;
    });
  }

  isFootnote(anchor: HTMLAnchorElement, href: string): boolean {
    if (this.hasNoteType(anchor)) return true;
    const target = this.viewManager.resolveHref(href);
    if (!target) return false;
    return this.viewManager.getSection(target.index)?.linear === 'no';
  }

  async open(anchor: HTMLAnchorElement, href: string): Promise<void> {
    const target = this.viewManager.resolveHref(href);
    const section = target ? this.viewManager.getSection(target.index) : null;

    if (!target?.anchor || !section?.createDocument) {
      console.warn(`Footnote ${href} could not be resolved to a section, navigating instead`);
      this.navigate(href);
      return;
    }

    let html: string;
    try {
      const doc = await Promise.resolve(section.createDocument());
      const element = target.anchor(doc);
      if (!element) {
        console.warn(`Footnote target for ${href} not found in section ${target.index}`);
        this.navigate(href);
        return;
      }
      html = this.extractHtml(element);
    } catch (error) {
      console.warn(`Failed to load footnote content for ${href}`, error);
      this.navigate(href);
      return;
    }

    if (!html) {
      console.warn(`Footnote ${href} resolved to empty content, navigating instead`);
      this.navigate(href);
      return;
    }

    const {position, showBelow} = this.computePlacement(anchor);
    this._state.set({visible: true, position, showBelow, html, href});
  }

  close(): void {
    this._state.set(this.defaultState);
  }

  openFull(): void {
    const {href} = this._state();
    this.close();
    if (href) this.navigate(href);
  }

  private navigate(href: string): void {
    this.viewManager.goTo(href).subscribe({
      error: (error: unknown) => console.warn(`Could not navigate to ${href}`, error)
    });
  }

  private hasNoteType(anchor: HTMLAnchorElement): boolean {
    const value = anchor.getAttributeNS(EPUB_OPS_NS, 'type')
      ?? anchor.getAttribute('epub:type')
      ?? anchor.getAttribute('type')
      ?? '';
    return value.split(/\s+/).some(token => NOTE_TYPES.includes(token.toLowerCase()));
  }

  private extractHtml(element: Element): string {
    const clone = element.cloneNode(true) as Element;
    clone.querySelectorAll(STRIPPED_TAGS).forEach(node => node.remove());
    const elements: Element[] = [clone, ...Array.from(clone.querySelectorAll('*'))];
    for (const node of elements) {
      // a note's back-reference must not navigate out of the popover
      node.removeAttribute('href');
      // the book's own colours (inline styles, class rules from a stylesheet we
      // do not load, legacy color attributes) would override the popover theme
      // and can leave the note invisible on the reader's page background
      node.removeAttribute('style');
      node.removeAttribute('class');
      node.removeAttribute('color');
      node.removeAttribute('bgcolor');
    }
    return clone.innerHTML.trim();
  }

  private computePlacement(anchor: HTMLAnchorElement): {
    position: { x: number; y: number };
    showBelow: boolean;
  } {
    const rect = anchor.getBoundingClientRect();
    const frame = anchor.ownerDocument.defaultView?.frameElement as HTMLElement | null;
    const frameRect = frame?.getBoundingClientRect();
    const offsetLeft = frameRect?.left ?? 0;
    const offsetTop = frameRect?.top ?? 0;

    const top = offsetTop + rect.top;
    const bottom = offsetTop + rect.bottom;
    const showBelow = top < window.innerHeight / 2;

    const x = Math.min(
      Math.max(offsetLeft + rect.left + rect.width / 2, POPUP_EDGE_MARGIN_PX),
      window.innerWidth - POPUP_EDGE_MARGIN_PX
    );

    return {
      position: {x, y: showBelow ? bottom + POPUP_GAP_PX : top - POPUP_GAP_PX},
      showBelow
    };
  }
}
