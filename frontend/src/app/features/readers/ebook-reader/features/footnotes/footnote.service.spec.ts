import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {ReaderViewManagerService} from '../../core/view-manager.service';
import {ReaderFootnoteService} from './footnote.service';

const EPUB_OPS_NS = 'http://www.idpf.org/2007/ops';

function makeAnchor(epubType?: string): HTMLAnchorElement {
  const doc = document.implementation.createHTMLDocument('note test');
  const anchor = doc.createElement('a');
  anchor.setAttribute('href', '#n_3');
  if (epubType) anchor.setAttributeNS(EPUB_OPS_NS, 'epub:type', epubType);
  doc.body.append(anchor);
  return anchor;
}

function noteDocument(html: string): Document {
  const doc = document.implementation.createHTMLDocument('notes');
  doc.body.innerHTML = html;
  return doc;
}

describe('ReaderFootnoteService', () => {
  let service: ReaderFootnoteService;
  const viewManager = {
    setLinkHandler: vi.fn(),
    resolveHref: vi.fn(),
    getSection: vi.fn(),
    goTo: vi.fn()
  };

  beforeEach(() => {
    viewManager.setLinkHandler.mockReset();
    viewManager.resolveHref.mockReset();
    viewManager.getSection.mockReset();
    viewManager.goTo.mockReset();
    viewManager.goTo.mockReturnValue(of(undefined));

    TestBed.configureTestingModule({
      providers: [
        ReaderFootnoteService,
        {provide: ReaderViewManagerService, useValue: viewManager}
      ]
    });

    service = TestBed.inject(ReaderFootnoteService);
  });

  describe('isFootnote', () => {
    it('accepts an anchor marked as a noteref', () => {
      viewManager.resolveHref.mockReturnValue(null);

      expect(service.isFootnote(makeAnchor('noteref'), '#n_3')).toBe(true);
    });

    it('accepts a target in a linear="no" section', () => {
      viewManager.resolveHref.mockReturnValue({index: 13, anchor: () => null});
      viewManager.getSection.mockReturnValue({linear: 'no'});

      expect(service.isFootnote(makeAnchor(), '#n_3')).toBe(true);
    });

    it('rejects an ordinary link into a linear section', () => {
      viewManager.resolveHref.mockReturnValue({index: 2, anchor: () => null});
      viewManager.getSection.mockReturnValue({linear: 'yes'});

      expect(service.isFootnote(makeAnchor(), 'chapter2.xhtml#top')).toBe(false);
    });

    it('rejects a link it cannot resolve', () => {
      viewManager.resolveHref.mockReturnValue(null);

      expect(service.isFootnote(makeAnchor(), 'https://example.com/')).toBe(false);
    });
  });

  describe('open', () => {
    it('publishes sanitized note content', async () => {
      const doc = noteDocument(
        '<section id="n_3"><p>Note <a href="#ref">back</a></p><img src="x.png" alt=""></section>'
      );
      viewManager.resolveHref.mockReturnValue({
        index: 13,
        anchor: (d: Document) => d.getElementById('n_3')
      });
      viewManager.getSection.mockReturnValue({
        linear: 'no',
        createDocument: () => Promise.resolve(doc)
      });

      await service.open(makeAnchor('noteref'), '#n_3');

      const state = service.state();
      expect(state.visible).toBe(true);
      expect(state.href).toBe('#n_3');
      expect(state.html).toContain('Note');
      expect(state.html).not.toContain('<img');
      expect(state.html).not.toContain('href=');
      expect(viewManager.goTo).not.toHaveBeenCalled();
    });

    it('accepts a section that returns the document synchronously', async () => {
      const doc = noteDocument('<p id="n_3">Sync note</p>');
      viewManager.resolveHref.mockReturnValue({
        index: 13,
        anchor: (d: Document) => d.getElementById('n_3')
      });
      viewManager.getSection.mockReturnValue({linear: 'no', createDocument: () => doc});

      await service.open(makeAnchor('noteref'), '#n_3');

      expect(service.state().html).toContain('Sync note');
    });

    it('falls back to navigation when the note document fails to load', async () => {
      viewManager.resolveHref.mockReturnValue({index: 13, anchor: () => null});
      viewManager.getSection.mockReturnValue({
        linear: 'no',
        createDocument: () => Promise.reject(new Error('boom'))
      });

      await service.open(makeAnchor('noteref'), '#n_3');

      expect(service.state().visible).toBe(false);
      expect(viewManager.goTo).toHaveBeenCalledWith('#n_3');
    });

    it('falls back to navigation when the anchor matches nothing', async () => {
      const doc = noteDocument('<p>unrelated</p>');
      viewManager.resolveHref.mockReturnValue({index: 13, anchor: () => null});
      viewManager.getSection.mockReturnValue({linear: 'no', createDocument: () => doc});

      await service.open(makeAnchor('noteref'), '#n_3');

      expect(service.state().visible).toBe(false);
      expect(viewManager.goTo).toHaveBeenCalledWith('#n_3');
    });

    it('falls back to navigation when the href does not resolve at all', async () => {
      viewManager.resolveHref.mockReturnValue(null);

      await service.open(makeAnchor('noteref'), '#n_3');

      expect(service.state().visible).toBe(false);
      expect(viewManager.goTo).toHaveBeenCalledWith('#n_3');
    });
  });

  it('registers a link handler that cancels navigation for footnotes', () => {
    service.register();

    expect(viewManager.setLinkHandler).toHaveBeenCalled();
    const handler = viewManager.setLinkHandler.mock.calls[0][0] as
      (a: HTMLAnchorElement, href: string) => boolean;

    viewManager.resolveHref.mockReturnValue({
      index: 13,
      anchor: (d: Document) => d.getElementById('n_3')
    });
    viewManager.getSection.mockReturnValue({
      linear: 'no',
      createDocument: () => noteDocument('<p id="n_3">note</p>')
    });

    expect(handler(makeAnchor('noteref'), '#n_3')).toBe(true);
  });

  it('leaves ordinary links to foliate', () => {
    service.register();
    const handler = viewManager.setLinkHandler.mock.calls[0][0] as
      (a: HTMLAnchorElement, href: string) => boolean;

    viewManager.resolveHref.mockReturnValue({index: 2, anchor: () => null});
    viewManager.getSection.mockReturnValue({linear: 'yes'});

    expect(handler(makeAnchor(), 'chapter2.xhtml#top')).toBe(false);
  });

  it('navigates and closes on openFull', async () => {
    const doc = noteDocument('<p id="n_3">note</p>');
    viewManager.resolveHref.mockReturnValue({
      index: 13,
      anchor: (d: Document) => d.getElementById('n_3')
    });
    viewManager.getSection.mockReturnValue({linear: 'no', createDocument: () => doc});
    await service.open(makeAnchor('noteref'), '#n_3');

    service.openFull();

    expect(service.state().visible).toBe(false);
    expect(viewManager.goTo).toHaveBeenCalledWith('#n_3');
  });

  it('closes without navigating', async () => {
    const doc = noteDocument('<p id="n_3">note</p>');
    viewManager.resolveHref.mockReturnValue({
      index: 13,
      anchor: (d: Document) => d.getElementById('n_3')
    });
    viewManager.getSection.mockReturnValue({linear: 'no', createDocument: () => doc});
    await service.open(makeAnchor('noteref'), '#n_3');

    service.close();

    expect(service.state().visible).toBe(false);
    expect(viewManager.goTo).not.toHaveBeenCalled();
  });
});
