import { Component, inject, OnInit, OnDestroy, AfterViewInit, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DynamicDialogRef, DynamicDialogConfig } from 'primeng/dynamicdialog';
import { AutoComplete, AutoCompleteSelectEvent } from 'primeng/autocomplete';
import { Button } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { debounceTime, distinctUntilChanged, map, startWith, Subject, switchMap, takeUntil } from 'rxjs';
import { BookFileService } from '../../service/book-file.service';
import { Book } from '../../model/book.model';
import { MessageService, PrimeTemplate } from 'primeng/api';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AppSettingsService } from '../../../../shared/service/app-settings.service';
import {AppBooksApiService} from '../../service/app-books-api.service';

@Component({
  selector: 'app-book-file-attacher',
  standalone: true,
  imports: [
    FormsModule,
    AutoComplete,
    Button,
    Checkbox,
    TranslocoDirective,
    TranslocoPipe,
    PrimeTemplate,
  ],
  templateUrl: './book-file-attacher.component.html',
  styleUrls: ['./book-file-attacher.component.scss']
})
export class BookFileAttacherComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('autocompleteWrapper') autocompleteWrapper!: ElementRef;

  sourceBooks: Book[] = [];
  targetBook: Book | null = null;
  moveFiles = false;
  isAttaching = false;
  searchQuery = '';
  filteredBooks: Book[] = [];
  autocomplePanelStyle: Record<string, string> = {};

  private destroy$ = new Subject<void>();
  private searchRequests = new Subject<string>();

  private readonly t = inject(TranslocoService);
  private readonly appSettingsService = inject(AppSettingsService);
  private dialogRef = inject(DynamicDialogRef);
  private config = inject(DynamicDialogConfig);
  private appBooksApi = inject(AppBooksApiService);
  private bookFileService = inject(BookFileService);
  private messageService = inject(MessageService);

  ngAfterViewInit(): void {
    setTimeout(() => {
      const width = this.autocompleteWrapper?.nativeElement?.offsetWidth;
      if (width) {
        this.autocomplePanelStyle = { 'width': `${width}px`, 'max-width': `${width}px` };
      }
    });
  }

  ngOnInit(): void {
    // Support both single book and multiple books
    if (this.config.data.sourceBook) {
      this.sourceBooks = [this.config.data.sourceBook];
    } else if (this.config.data.sourceBooks) {
      this.sourceBooks = this.config.data.sourceBooks;
    }

    if (this.sourceBooks.length === 0) {
      this.closeDialog();
      return;
    }

    const settings = this.appSettingsService.appSettings();
    if (settings) {
      this.moveFiles = settings.metadataPersistenceSettings?.moveFilesToLibraryPattern ?? false;
    }

    // Get the library ID from first source book (all should be same library)
    const libraryId = this.sourceBooks[0].libraryId;
    const sourceBookIds = new Set(this.sourceBooks.map(b => b.id));

    this.searchRequests.pipe(
      startWith(''),
      debounceTime(150),
      distinctUntilChanged(),
      switchMap(query => this.appBooksApi.getPage(
        {libraryId},
        {field: 'title', dir: 'asc'},
        20 + sourceBookIds.size,
        query,
      )),
      map(books => books.filter(book => !sourceBookIds.has(book.id)).slice(0, 20)),
      takeUntil(this.destroy$),
    ).subscribe(books => {
      this.filteredBooks = books;
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get isBulkMode(): boolean {
    return this.sourceBooks.length > 1;
  }

  filterBooks(event: { query: string; }): void {
    this.searchRequests.next(event.query.trim());
  }

  onBookSelect(event: AutoCompleteSelectEvent): void {
    this.targetBook = event.value as Book;
  }

  onBookClear(): void {
    this.targetBook = null;
  }

  getBookDisplayName(book: Book): string {
    const title = book.metadata?.title || `Book #${book.id}`;
    const authors = book.metadata?.authors?.join(', ');
    return authors ? `${title} - ${authors}` : title;
  }

  getSourceFileInfo(book: Book): string {
    const file = book.primaryFile;
    if (!file) return this.t.translate('book.fileAttacher.unknownFile');
    const format = file.extension?.toUpperCase() || file.bookType || this.t.translate('book.fileAttacher.unknownFormat');
    return `${format} - ${file.fileName || this.t.translate('book.fileAttacher.unknownFilename')}`;
  }

  canAttach(): boolean {
    return !!this.targetBook && !this.isAttaching;
  }

  attach(): void {
    if (!this.targetBook) return;

    this.isAttaching = true;

    const sourceBookIds = this.sourceBooks.map(b => b.id);

    this.bookFileService.attachBookFiles(
      this.targetBook.id,
      sourceBookIds,
      this.moveFiles
    ).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.dialogRef.close({ success: true });
      },
      error: () => {
        this.isAttaching = false;
      }
    });
  }

  closeDialog(): void {
    this.dialogRef.close();
  }
}
