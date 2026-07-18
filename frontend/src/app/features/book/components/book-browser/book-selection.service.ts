import {computed, Injectable, signal} from '@angular/core';
import {Book} from '../../model/book.model';

export interface CheckboxClickEvent {
  index: number;
  book: Book;
  selected: boolean;
  shiftKey: boolean;
}

@Injectable({providedIn: 'root'})
export class BookSelectionService {
  private readonly _selectedBooks = signal<Set<number>>(new Set());
  readonly selectedBooks = this._selectedBooks.asReadonly();
  readonly selectedCount = computed(() => this._selectedBooks().size);

  private currentBooks: Book[] = [];
  // Global index of currentBooks[0]. Non-zero once the paginated list evicts earlier pages
  // (maxPages), so currentBooks is a sliding window rather than a prefix from index 0. Checkbox
  // events carry the global virtual index, which must be offset by this to hit the right entry.
  private currentBooksOffset = 0;
  private lastSelectedIndex: number | null = null;

  setCurrentBooks(books: Book[], firstLoadedIndex = 0): void {
    this.currentBooks = books;
    this.currentBooksOffset = firstLoadedIndex;
  }

  getSelectableBookIds(book: Book): number[] {
    return book.seriesBooks?.length
      ? book.seriesBooks.map(seriesBook => seriesBook.id)
      : [book.id];
  }

  isBookSelected(book: Book): boolean {
    const selectedIds = this._selectedBooks();
    const ids = this.getSelectableBookIds(book);
    return ids.every(id => selectedIds.has(id));
  }

  selectBook(book: Book): void {
    this._selectedBooks.update(current => {
      const next = new Set(current);
      this.getSelectableBookIds(book).forEach(bookId => next.add(bookId));
      return next;
    });
  }

  deselectBook(book: Book): void {
    this._selectedBooks.update(current => {
      const next = new Set(current);
      this.getSelectableBookIds(book).forEach(bookId => next.delete(bookId));
      return next;
    });
  }

  handleCheckboxClick(event: CheckboxClickEvent): void {
    const {index, book, selected, shiftKey} = event;

    if (!shiftKey || this.lastSelectedIndex === null) {
      this.handleBookSelection(book, selected);
      this.lastSelectedIndex = index;
    } else {
      const start = Math.min(this.lastSelectedIndex, index);
      const end = Math.max(this.lastSelectedIndex, index);
      const isUnselectingRange = !selected;

      for (let i = start; i <= end; i++) {
        // start/end are global virtual indices; map into the loaded window. Books outside the
        // retained window (e.g. a range spanning pages evicted after a deep scroll) resolve to
        // undefined and are skipped.
        const rangeBook = this.currentBooks[i - this.currentBooksOffset];
        if (!rangeBook) continue;
        this.handleBookSelection(rangeBook, !isUnselectingRange);
      }
    }
  }

  handleBookSelection(book: Book, selected: boolean): void {
    if (selected) {
      this.selectBook(book);
    } else {
      this.deselectBook(book);
    }
  }

  selectAll(allBookIds?: number[]): void {
    if (allBookIds && allBookIds.length > 0) {
      this._selectedBooks.set(new Set(allBookIds));
      return;
    }

    if (!this.currentBooks || this.currentBooks.length === 0) return;

    this._selectedBooks.update(current => {
      const next = new Set(current);
      for (const book of this.currentBooks) {
        this.getSelectableBookIds(book).forEach(bookId => next.add(bookId));
      }
      return next;
    });
  }

  deselectAll(): void {
    this._selectedBooks.set(new Set());
    this.lastSelectedIndex = null;
  }

  setSelectedBooks(bookIds: Set<number>): void {
    this._selectedBooks.set(new Set(bookIds));
  }
}
