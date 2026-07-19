import {Component, DestroyRef, effect, inject, Input, OnChanges, OnInit, SimpleChanges} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';

import {MetadataRefreshType} from '../../model/request/metadata-refresh-type.enum';
import {MetadataRefreshOptions} from '../../model/request/metadata-refresh-options.model';

import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {BookService} from '../../../book/service/book.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {Book} from '../../../book/model/book.model';
import {FormsModule} from '@angular/forms';
import {MetadataFetchOptionsComponent} from '../metadata-options-dialog/metadata-fetch-options/metadata-fetch-options.component';
import {Button} from 'primeng/button';

@Component({
  selector: 'app-multi-book-metadata-fetch-component',
  standalone: true,
  templateUrl: './multi-book-metadata-fetch-component.html',
  styleUrl: './multi-book-metadata-fetch-component.scss',
  imports: [MetadataFetchOptionsComponent, FormsModule, Button],
})
export class MultiBookMetadataFetchComponent implements OnInit, OnChanges {
  @Input() dialogData?: {
    libraryId?: number | null;
    bookIds?: number[];
    metadataRefreshType?: MetadataRefreshType;
  };

  bookIds: number[] = [];
  libraryId: number | null = null;
  booksToShow: Book[] = [];
  metadataRefreshType: MetadataRefreshType = MetadataRefreshType.BOOKS;
  currentMetadataOptions!: MetadataRefreshOptions;

  private readonly dynamicDialogConfig = inject(DynamicDialogConfig);
  private readonly destroyRef = inject(DestroyRef);
  dialogRef = inject(DynamicDialogRef);
  private readonly bookService = inject(BookService);
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly messageService = inject(MessageService);
  expanded = false;

  constructor() {
    effect(() => {
      const settings = this.appSettingsService.appSettings();
      if (settings) {
        this.currentMetadataOptions = settings.defaultMetadataRefreshOptions;
      }
    });
  }

  ngOnInit(): void {
    this.applyContext(this.dialogData ?? this.dynamicDialogConfig.data ?? {});
  }

  ngOnChanges(changes: SimpleChanges): void {
    if ('dialogData' in changes) {
      this.applyContext(changes['dialogData'].currentValue ?? {});
    }
  }

  private applyContext(context: {
    libraryId?: number | null;
    bookIds?: number[];
    metadataRefreshType?: MetadataRefreshType;
  }): void {
    this.bookIds = context.bookIds ?? [];
    this.libraryId = context.libraryId ?? null;
    this.metadataRefreshType = context.metadataRefreshType ?? MetadataRefreshType.BOOKS;
    this.bookService.getBooksByIds(this.bookIds)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: books => this.booksToShow = books,
        error: () => {
          this.booksToShow = [];
          this.messageService.add({
            severity: 'error',
            summary: 'Error',
            detail: 'Failed to load the selected books.',
          });
        },
      });
  }

  get isLibraryRefresh(): boolean {
    return this.metadataRefreshType === MetadataRefreshType.LIBRARY;
  }
}
