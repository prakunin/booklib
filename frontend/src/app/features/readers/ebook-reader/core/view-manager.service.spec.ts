import {TestBed} from '@angular/core/testing';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {ReaderAnnotationService} from '../features/annotations/annotation-renderer.service';
import {EpubStreamingService} from './epub-streaming.service';
import {ReaderEventService} from './event.service';
import {ReaderViewManagerService} from './view-manager.service';

describe('ReaderViewManagerService', () => {
  let service: ReaderViewManagerService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ReaderViewManagerService,
        {provide: ReaderAnnotationService, useValue: {}},
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
});
