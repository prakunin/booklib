import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {ReaderAnnotationService} from '../features/annotations/annotation-renderer.service';
import {EpubStreamingService} from './epub-streaming.service';
import {ReaderEventService} from './event.service';
import {ReaderViewManagerService} from './view-manager.service';

describe('ReaderViewManagerService', () => {
  let service: ReaderViewManagerService;
  const annotationService = {
    addAnnotation: vi.fn(),
    deleteAnnotation: vi.fn(),
    showAnnotation: vi.fn(),
    addAnnotations: vi.fn(),
  };

  beforeEach(() => {
    annotationService.addAnnotation.mockReset();
    annotationService.deleteAnnotation.mockReset();
    annotationService.showAnnotation.mockReset();
    annotationService.addAnnotations.mockReset();

    TestBed.configureTestingModule({
      providers: [
        ReaderViewManagerService,
        {provide: ReaderAnnotationService, useValue: annotationService},
        {provide: ReaderEventService, useValue: {events$: {}}},
        {provide: EpubStreamingService, useValue: {}},
      ],
    });

    service = TestBed.inject(ReaderViewManagerService);
  });

  it('returns selection from the loaded document that actually has selected text', () => {
    const firstDoc = document.implementation.createHTMLDocument('first');
    Object.defineProperty(firstDoc, 'defaultView', {
      value: {
        getSelection: () => ({
          isCollapsed: true,
          rangeCount: 0,
        }),
      },
      configurable: true,
    });

    const secondDoc = document.implementation.createHTMLDocument('second');
    secondDoc.body.textContent = 'Second chapter text';
    const selectedRange = secondDoc.createRange();
    selectedRange.selectNodeContents(secondDoc.body);
    Object.defineProperty(secondDoc, 'defaultView', {
      value: {
        getSelection: () => ({
          isCollapsed: false,
          rangeCount: 1,
          getRangeAt: () => selectedRange,
        }),
      },
      configurable: true,
    });

    const getCFI = vi.fn(() => 'epubcfi(/6/8!/4/2:0)');
    Reflect.set(service, 'view', {
      renderer: {
        getContents: () => [{index: 7, doc: firstDoc}, {index: 8, doc: secondDoc}],
      },
      getCFI,
    });

    const selection = service.getSelection();

    expect(getCFI).toHaveBeenCalledWith(8, selectedRange);
    expect(selection).toMatchObject({
      text: 'Second chapter text',
      cfi: 'epubcfi(/6/8!/4/2:0)',
      index: 8,
    });
    expect(selection?.range).toBe(selectedRange);
  });

  it('shows an annotation when navigating directly to a CFI', () => {
    const view = {
      goTo: vi.fn(() => Promise.resolve()),
    };
    annotationService.showAnnotation.mockReturnValue(of(void 0));
    Reflect.set(service, 'view', view);

    service.goToAnnotation('epubcfi(/6/10)').subscribe();

    expect(annotationService.showAnnotation).toHaveBeenCalledWith(view, 'epubcfi(/6/10)');
    expect(view.goTo).not.toHaveBeenCalled();
  });

  it('falls back to plain navigation when showing an annotation fails', () => {
    const view = {
      goTo: vi.fn(() => Promise.resolve()),
    };
    annotationService.showAnnotation.mockReturnValue(throwError(() => new Error('missing overlay')));
    Reflect.set(service, 'view', view);

    service.goToAnnotation('epubcfi(/6/12)').subscribe();

    expect(view.goTo).toHaveBeenCalledWith('epubcfi(/6/12)');
  });

  describe('link resolution', () => {
    it('resolves an href through the book model', () => {
      const target = {index: 3, anchor: () => null};
      Reflect.set(service, 'view', {
        book: {resolveHref: (href: string) => (href === '#n_3' ? target : null)},
      });

      expect(service.resolveHref('#n_3')).toBe(target);
      expect(service.resolveHref('#missing')).toBeNull();
    });

    it('returns null when the book model throws', () => {
      Reflect.set(service, 'view', {
        book: {
          resolveHref: () => {
            throw new Error('bad href');
          },
        },
      });

      expect(service.resolveHref('#n_3')).toBeNull();
    });

    it('returns null when no book is open', () => {
      Reflect.set(service, 'view', null);

      expect(service.resolveHref('#n_3')).toBeNull();
      expect(service.getSection(0)).toBeNull();
    });

    it('returns the section at an index', () => {
      const sections = [{linear: 'yes'}, {linear: 'no'}];
      Reflect.set(service, 'view', {book: {sections}});

      expect(service.getSection(1)).toBe(sections[1]);
      expect(service.getSection(9)).toBeNull();
    });

    it('clears the link handler on destroy', () => {
      const eventService = TestBed.inject(ReaderEventService) as unknown as {destroy?: () => void};
      eventService.destroy = vi.fn();
      service.setLinkHandler(() => true);
      Reflect.set(service, 'view', null);

      service.destroy();

      expect(Reflect.get(service, 'linkHandler')).toBeNull();
    });
  });
});
