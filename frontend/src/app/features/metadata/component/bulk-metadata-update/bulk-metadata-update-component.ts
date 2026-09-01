import {Component, computed, inject, OnInit} from '@angular/core';
import {FormBuilder, FormGroup, FormsModule, ReactiveFormsModule} from '@angular/forms';

import {InputText} from '@openng/optimus-ui/inputtext';
import {Button} from '@openng/optimus-ui/button';
import {Tooltip} from '@openng/optimus-ui/tooltip';
import {DatePicker} from '@openng/optimus-ui/datepicker';
import {DynamicDialogConfig, DynamicDialogRef} from '@openng/optimus-ui/dynamicdialog';
import {MessageService} from '@openng/optimus-ui/api';
import {BookService} from '../../../book/service/book.service';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';
import {Book, BulkMetadataUpdateRequest} from '../../../book/model/book.model';
import {Checkbox} from '@openng/optimus-ui/checkbox';
import {AutoComplete, AutoCompleteSelectEvent} from '@openng/optimus-ui/autocomplete';
import {ProgressSpinner} from '@openng/optimus-ui/progressspinner';
import {AppBooksApiService} from '../../../book/service/app-books-api.service';
import {TranslocoDirective} from '@jsverse/transloco';

@Component({
  selector: 'app-bulk-metadata-update-component',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    FormsModule,
    InputText,
    Button,
    Tooltip,
    DatePicker,
    Checkbox,
    ProgressSpinner,
    AutoComplete,
    TranslocoDirective
],
  providers: [MessageService],
  templateUrl: './bulk-metadata-update-component.html',
  styleUrl: './bulk-metadata-update-component.scss'
})
export class BulkMetadataUpdateComponent implements OnInit {
  metadataForm!: FormGroup;
  bookIds: number[] = [];
  books: Book[] = [];
  showBookList = true;
  mergeCategories = true;
  mergeMoods = true;
  mergeTags = true;
  loading = false;
  selectedCoverFile: File | null = null;

  clearFields = {
    authors: false,
    publisher: false,
    language: false,
    seriesName: false,
    seriesTotal: false,
    publishedDate: false,
    genres: false,
    moods: false,
    tags: false,
  };

  private readonly config = inject(DynamicDialogConfig);
  readonly ref = inject(DynamicDialogRef);
  private readonly fb = inject(FormBuilder);
  private readonly bookService = inject(BookService);
  private readonly appBooksApi = inject(AppBooksApiService);
  private readonly bookMetadataManageService = inject(BookMetadataManageService);
  private readonly messageService = inject(MessageService);
  private readonly uniqueMetadata = computed(() => this.bookService.uniqueMetadata());

  get allAuthors(): string[] { return this.uniqueMetadata().authors; }
  get allGenres(): string[] { return this.uniqueMetadata().categories; }
  get allMoods(): string[] { return this.uniqueMetadata().moods; }
  get allTags(): string[] { return this.uniqueMetadata().tags; }
  get allPublishers(): string[] { return this.uniqueMetadata().publishers; }
  get allSeries(): string[] { return this.uniqueMetadata().series; }
  filteredGenres: string[] = [];
  filteredAuthors: string[] = [];
  filteredMoods: string[] = [];
  filteredTags: string[] = [];
  filteredPublishers: string[] = [];
  filteredSeries: string[] = [];

  filterGenres(event: { query: string }) {
    const query = event.query.toLowerCase();
    this.filteredGenres = this.allGenres.filter((cat) =>
      cat.toLowerCase().includes(query)
    );
  }

  filterAuthors(event: { query: string }) {
    const query = event.query.toLowerCase();
    this.filteredAuthors = this.allAuthors.filter((author) =>
      author.toLowerCase().includes(query)
    );
  }

  filterMoods(event: { query: string }) {
    const query = event.query.toLowerCase();
    this.filteredMoods = this.allMoods.filter((mood) =>
      mood.toLowerCase().includes(query)
    );
  }

  filterTags(event: { query: string }) {
    const query = event.query.toLowerCase();
    this.filteredTags = this.allTags.filter((tag) =>
      tag.toLowerCase().includes(query)
    );
  }

  filterPublishers(event: { query: string }) {
    const query = event.query.toLowerCase();
    this.filteredPublishers = this.allPublishers.filter((publisher) =>
      publisher.toLowerCase().includes(query)
    );
  }

  filterSeries(event: { query: string }) {
    const query = event.query.toLowerCase();
    this.filteredSeries = this.allSeries.filter((seriesName) =>
      seriesName.toLowerCase().includes(query)
    );
  }

  ngOnInit(): void {
    this.bookIds = this.config.data?.bookIds ?? [];
    this.appBooksApi.getBooksByIds(this.bookIds).subscribe(books => {
      this.books = books;
    });

    this.metadataForm = this.fb.group({
      authors: [],
      publisher: [''],
      language: [''],
      seriesName: [''],
      seriesTotal: [''],
      publishedDate: [null],
      genres: [],
      moods: [],
      tags: []
    });

  }

  onFieldClearToggle(field: keyof typeof this.clearFields): void {
    const control = this.metadataForm.get(field);
    if (!control) return;

    if (this.clearFields[field]) {
      control.disable();
      control.setValue(null);
    } else {
      control.enable();
    }
  }

  onAutoCompleteSelect(fieldName: string, event: AutoCompleteSelectEvent) {
    const values = (this.metadataForm.get(fieldName)?.value as string[]) || [];
    if (!values.includes(event.value as string)) {
      this.metadataForm.get(fieldName)?.setValue([...values, event.value as string]);
    }
    (event.originalEvent.target as HTMLInputElement).value = "";
  }

  onAutoCompleteKeyUp(fieldName: string, event: KeyboardEvent) {
    if (event.key === "Enter") {
      const input = event.target as HTMLInputElement;
      const value = input.value?.trim();
      if (value) {
        const values = this.metadataForm.get(fieldName)?.value || [];
        if (!values.includes(value)) {
          this.metadataForm.get(fieldName)?.setValue([...values, value]);
        }
        input.value = "";
      }
    }
  }

  onFormKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      if ((event.target as HTMLElement)?.tagName === 'BUTTON' &&
        (event.target as HTMLButtonElement)?.type === 'submit') {
        return;
      }
      event.preventDefault();
    }
  }

  private arrayFieldValue(clear: boolean, value: string[] | undefined): string[] | undefined {
    if (clear) {
      return [];
    }
    return value?.length ? value : undefined;
  }

  private textFieldValue(clear: boolean, value: string | undefined): string | undefined {
    if (clear) {
      return '';
    }
    return value?.trim() || undefined;
  }

  private publishedDateValue(clear: boolean, value: string | Date | null | undefined): string | null | undefined {
    if (clear) {
      return null;
    }
    return value ? new Date(value).toISOString().split('T')[0] : undefined;
  }

  onSubmit(): void {
    if (!this.metadataForm.valid) return;

    const formValue = this.metadataForm.value;

    const payload: BulkMetadataUpdateRequest = {
      bookIds: this.bookIds,

      authors: this.arrayFieldValue(this.clearFields.authors, formValue.authors),
      clearAuthors: this.clearFields.authors,

      publisher: this.textFieldValue(this.clearFields.publisher, formValue.publisher),
      clearPublisher: this.clearFields.publisher,

      language: this.textFieldValue(this.clearFields.language, formValue.language),
      clearLanguage: this.clearFields.language,

      seriesName: this.textFieldValue(this.clearFields.seriesName, formValue.seriesName),
      clearSeriesName: this.clearFields.seriesName,

      seriesTotal: this.clearFields.seriesTotal ? null : (formValue.seriesTotal || undefined),
      clearSeriesTotal: this.clearFields.seriesTotal,

      publishedDate: this.publishedDateValue(this.clearFields.publishedDate, formValue.publishedDate),
      clearPublishedDate: this.clearFields.publishedDate,

      genres: this.arrayFieldValue(this.clearFields.genres, formValue.genres),
      clearGenres: this.clearFields.genres,

      moods: this.arrayFieldValue(this.clearFields.moods, formValue.moods),
      clearMoods: this.clearFields.moods,

      tags: this.arrayFieldValue(this.clearFields.tags, formValue.tags),
      clearTags: this.clearFields.tags,

      mergeCategories: this.mergeCategories,
      mergeMoods: this.mergeMoods,
      mergeTags: this.mergeTags
    };

    this.loading = true;
    this.bookMetadataManageService.updateBooksMetadata(payload).subscribe({
      next: () => {
        if (this.selectedCoverFile) {
          this.bookMetadataManageService.bulkUploadCover(this.bookIds, this.selectedCoverFile).subscribe({
            next: () => {
              this.loading = false;
              this.messageService.add({
                severity: 'success',
                summary: 'Metadata & Cover Updated',
                detail: 'Books updated and cover upload started. Refresh the page when complete.'
              });
              this.ref.close(true);
            },
            error: err => {
              console.error('Bulk cover upload failed:', err);
              this.loading = false;
              this.messageService.add({
                severity: 'warn',
                summary: 'Partial Success',
                detail: 'Metadata updated but cover upload failed'
              });
              this.ref.close(true);
            }
          });
        } else {
          this.loading = false;
          this.messageService.add({
            severity: 'success',
            summary: 'Metadata Updated',
            detail: 'Books updated successfully'
          });
          this.ref.close(true);
        }
      },
      error: err => {
        console.error('Bulk metadata update failed:', err);
        this.loading = false;
        this.messageService.add({
          severity: 'error',
          summary: 'Update Failed',
          detail: 'An error occurred while updating book metadata'
        });
      }
    });
  }

  onCoverFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedCoverFile = input.files[0];
    }
  }

  clearCoverFile(): void {
    this.selectedCoverFile = null;
  }
}
