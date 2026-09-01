import {Component, inject, Input, OnInit, signal} from '@angular/core';
import {MessageService} from '@openng/optimus-ui/api';
import {FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, Validators} from '@angular/forms';
import {BookCoverService, CoverFetchRequest, CoverImage} from '../../../../shared/services/book-cover.service';
import {finalize} from 'rxjs/operators';
import {Button} from '@openng/optimus-ui/button';
import {InputText} from '@openng/optimus-ui/inputtext';
import {ProgressSpinner} from '@openng/optimus-ui/progressspinner';
import {DynamicDialogConfig, DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {BookService} from '../../../book/service/book.service';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';
import {Image} from '@openng/optimus-ui/image';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';

@Component({
  selector: 'app-cover-search',
  templateUrl: './cover-search.component.html',
  imports: [
    Button,
    ReactiveFormsModule,
    FormsModule,
    InputText,
    ProgressSpinner,
    Image,
    Tooltip,
    TranslocoDirective
  ],
  styleUrls: ['./cover-search.component.scss']
})
export class CoverSearchComponent implements OnInit {
  @Input() bookId!: number;
  searchForm: FormGroup;
  coverImages: CoverImage[] = [];
  loading = signal(false);
  hasSearched = signal(false);
  coverType: 'ebook' | 'audiobook' = 'ebook';

  private readonly fb = inject(FormBuilder);
  private readonly bookCoverService = inject(BookCoverService);
  private readonly dynamicDialogConfig = inject(DynamicDialogConfig);
  protected dynamicDialogRef = inject(DynamicDialogRef);
  protected bookService = inject(BookService);
  private readonly bookMetadataManageService = inject(BookMetadataManageService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  constructor() {
    this.searchForm = this.fb.group({
      title: ['', Validators.required],
      author: ['']
    });
  }

  ngOnInit() {
    this.bookId = this.dynamicDialogConfig.data.bookId;
    // The metadata center already has the full book detail. Prefer that object so the
    // search fields are populated even when the paginated catalog does not contain this book.
    // Keep the service lookup as a fallback for callers that only provide an id.
    const book = this.dynamicDialogConfig.data.book ?? this.bookService.findBookById(this.bookId);

    // Use explicitly provided coverType, or auto-detect based on primary file
    if (this.dynamicDialogConfig.data.coverType) {
      this.coverType = this.dynamicDialogConfig.data.coverType;
    } else if (book?.primaryFile?.bookType === 'AUDIOBOOK') {
      this.coverType = 'audiobook';
    } else {
      this.coverType = 'ebook';
    }

    if (book) {
      this.searchForm.patchValue({
        title: book.metadata?.title || '',
        author: book.metadata?.authors && book.metadata?.authors.length > 0 ? book.metadata?.authors[0] : ''
      });

      if (this.searchForm.valid) {
        this.onSearch();
      }
    }
  }

  onSearch() {
    if (this.searchForm.valid) {
      this.loading.set(true);
      this.coverImages = [];
      const request: CoverFetchRequest = {
        bookId: this.bookId,
        title: this.searchForm.value.title,
        author: this.searchForm.value.author,
        coverType: this.coverType
      };

      this.bookCoverService.fetchBookCovers(request)
        .pipe(finalize(() => {
          this.loading.set(false);
          this.hasSearched.set(true);
        }))
        .subscribe({
          next: (image) => {
            this.coverImages.push(image);
            this.coverImages.sort((a, b) => a.index - b.index);
          },
          error: (error) => {
            console.error('Error fetching covers:', error);
          }
        });
    }
  }

  selectAndSave(image: CoverImage) {
    const uploadObservable = this.coverType === 'audiobook'
      ? this.bookMetadataManageService.uploadAudiobookCoverFromUrl(this.bookId, image.url)
      : this.bookMetadataManageService.uploadCoverFromUrl(this.bookId, image.url);

    uploadObservable.subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('metadata.coverSearch.toast.coverUpdatedSummary'),
          detail: this.coverType === 'audiobook'
            ? this.t.translate('metadata.coverSearch.toast.audiobookCoverUpdatedDetail')
            : this.t.translate('metadata.coverSearch.toast.ebookCoverUpdatedDetail')
        });
        // The cover endpoints return an empty response. Refresh the detail query before
        // closing so the already-open metadata view receives the new cover timestamp and
        // immediately switches from the placeholder to the uploaded image.
        this.bookService.fetchFreshBookDetail(this.bookId, true)
          .catch(() => undefined)
          .finally(() => this.dynamicDialogRef.close(true));
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('metadata.coverSearch.toast.coverUpdateFailedSummary'),
          detail: err?.message || this.t.translate('metadata.coverSearch.toast.coverUpdateFailedDetail')
        });
      }
    });
  }

  onClear() {
    this.searchForm.reset();
    this.coverImages = [];
    this.hasSearched.set(false);
  }
}
