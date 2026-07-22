import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {afterEach, describe, expect, it} from 'vitest';
import {TranslocoService} from '@jsverse/transloco';
import {LibraryFilterService} from './library-filter.service';
import {LibraryService} from '../../../../book/service/library.service';
import {Library} from '../../../../book/model/library.model';

describe('LibraryFilterService', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('builds sorted options from the libraries endpoint without consulting books', () => {
    const libraries = signal([
      {id: 2, name: 'Beta'} as Library,
      {id: 1, name: 'Alpha'} as Library
    ]);
    TestBed.configureTestingModule({providers: [
      LibraryFilterService,
      {provide: LibraryService, useValue: {libraries}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}}
    ]});
    const service = TestBed.inject(LibraryFilterService);
    expect(service.libraryOptions()).toEqual([
      {id: null, name: 'statsLibrary.libraryFilter.allLibraries'},
      {id: 1, name: 'Alpha'},
      {id: 2, name: 'Beta'}
    ]);
    service.setSelectedLibrary(2);
    expect(service.selectedLibrary()).toBe(2);
    service.setSelectedLibrary(99);
    expect(service.selectedLibrary()).toBeNull();
  });
});
