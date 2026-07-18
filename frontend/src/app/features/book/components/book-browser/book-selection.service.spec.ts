import {TestBed} from '@angular/core/testing';
import {beforeEach, describe, expect, it} from 'vitest';

import {BookSelectionService} from './book-selection.service';
import {Book} from '../../model/book.model';

function makeBook(id: number, overrides: Partial<Book> = {}): Book {
  return {
    id,
    libraryId: 1,
    libraryName: 'Library',
    metadata: {bookId: id, title: `Book ${id}`},
    ...overrides
  };
}

describe('BookSelectionService', () => {
  let service: BookSelectionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [BookSelectionService]
    });

    service = TestBed.inject(BookSelectionService);
  });

  it('selects and deselects single books and series groups', () => {
    const singleBook = makeBook(1);
    const seriesBook = makeBook(2, {
      seriesBooks: [makeBook(2), makeBook(3)]
    });

    service.selectBook(singleBook);
    service.selectBook(seriesBook);

    expect(service.selectedBooks()).toEqual(new Set([1, 2, 3]));
    expect(service.selectedCount()).toBe(3);

    service.deselectBook(makeBook(1));
    service.deselectBook(seriesBook);

    expect(service.selectedBooks()).toEqual(new Set());
    expect(service.selectedCount()).toBe(0);
  });

  it('tracks checkbox clicks with and without shift selection', () => {
    const books = [makeBook(1), makeBook(2), makeBook(3), makeBook(4)];
    service.setCurrentBooks(books);

    service.handleCheckboxClick({index: 1, book: books[1], selected: true, shiftKey: false});
    expect(service.selectedBooks()).toEqual(new Set([2]));

    service.handleCheckboxClick({index: 3, book: books[3], selected: true, shiftKey: true});
    expect(service.selectedBooks()).toEqual(new Set([2, 3, 4]));

    service.handleCheckboxClick({index: 3, book: books[3], selected: false, shiftKey: true});
    expect(service.selectedBooks()).toEqual(new Set());
  });

  it('maps shift-click range selection through the loaded-window offset', () => {
    // After maxPages evicts earlier pages the loaded list is a window: books 100..103 sit at global
    // virtual indices 100..103, so checkbox events (which carry the global index) must be offset.
    const windowBooks = [makeBook(100), makeBook(101), makeBook(102), makeBook(103)];
    service.setCurrentBooks(windowBooks, 100);

    service.handleCheckboxClick({index: 100, book: windowBooks[0], selected: true, shiftKey: false});
    expect(service.selectedBooks()).toEqual(new Set([100]));

    // Shift-click the book at global index 102 selects the in-window range 100..102.
    service.handleCheckboxClick({index: 102, book: windowBooks[2], selected: true, shiftKey: true});
    expect(service.selectedBooks()).toEqual(new Set([100, 101, 102]));
  });

  it('selects all current books, resets selection, and clones selected ids', () => {
    expect(() => service.selectAll()).not.toThrow();

    const books = [makeBook(1), makeBook(2)];
    service.setCurrentBooks(books);
    service.selectAll();
    expect(service.selectedBooks()).toEqual(new Set([1, 2]));

    const selectedIds = new Set([4, 5]);
    service.setSelectedBooks(selectedIds);
    selectedIds.add(6);

    expect(service.selectedBooks()).toEqual(new Set([4, 5]));

    service.deselectAll();
    expect(service.selectedBooks()).toEqual(new Set());
    expect(service.selectedCount()).toBe(0);
  });

  it('selects all from provided book IDs when passed to selectAll', () => {
    const books = [makeBook(1), makeBook(2)];
    service.setCurrentBooks(books);

    service.selectAll([10, 20, 30, 40, 50]);
    expect(service.selectedBooks()).toEqual(new Set([10, 20, 30, 40, 50]));
    expect(service.selectedCount()).toBe(5);
  });
});
