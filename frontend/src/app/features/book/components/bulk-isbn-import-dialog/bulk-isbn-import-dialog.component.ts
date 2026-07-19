import {Component, effect, inject} from '@angular/core';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {FormsModule} from '@angular/forms';
import {Button} from 'primeng/button';
import {Select} from 'primeng/select';
import {Textarea} from 'primeng/textarea';
import {Tooltip} from 'primeng/tooltip';
import {ProgressBar} from 'primeng/progressbar';
import {FileUpload, FileSelectEvent} from 'primeng/fileupload';
import {BookService} from '../../service/book.service';
import {BookMetadataService} from '../../service/book-metadata.service';
import {LibraryService} from '../../service/library.service';
import {Library} from '../../model/library.model';
import {BookMetadata, CreatePhysicalBookRequest} from '../../model/book.model';
import {TranslocoDirective} from '@jsverse/transloco';
import {Tabs, TabList, Tab, TabPanels, TabPanel} from 'primeng/tabs';
import {firstValueFrom} from 'rxjs';
import {AppBooksApiService} from '../../service/app-books-api.service';

const MAX_ISBN_COUNT = 500;
const MAX_FILE_SIZE_BYTES = 1_048_576; // 1 MB
const DELAY_BETWEEN_REQUESTS_MS = 300;
const RETRY_DELAY_MS = 2000;

export type IsbnEntryStatus = 'pending' | 'looking-up' | 'created' | 'created-no-metadata' | 'failed';

export interface IsbnEntry {
  isbn: string;
  status: IsbnEntryStatus;
  title?: string;
  error?: string;
}

export interface SkippedEntry {
  value: string;
  reason: string;
}

type ImportPhase = 'upload' | 'processing' | 'summary';

@Component({
  selector: 'app-bulk-isbn-import-dialog',
  standalone: true,
  templateUrl: './bulk-isbn-import-dialog.component.html',
  imports: [
    FormsModule,
    Button,
    Select,
    Textarea,
    Tooltip,
    ProgressBar,
    FileUpload,
    TranslocoDirective,
    Tabs,
    TabList,
    Tab,
    TabPanels,
    TabPanel,
  ],
  styleUrl: './bulk-isbn-import-dialog.component.scss',
})
export class BulkIsbnImportDialogComponent {
  private readonly dynamicDialogRef = inject(DynamicDialogRef);
  private readonly dialogConfig = inject(DynamicDialogConfig);
  private readonly bookService = inject(BookService);
  private readonly appBooksApi = inject(AppBooksApiService);
  private readonly bookMetadataService = inject(BookMetadataService);
  private readonly libraryService = inject(LibraryService);

  selectedLibraryId: number | null = null;

  phase: ImportPhase = 'upload';
  pasteText = '';
  entries: IsbnEntry[] = [];
  skipped: SkippedEntry[] = [];
  duplicatesRemoved = 0;
  showSkipped = false;

  processedCount = 0;
  createdCount = 0;
  noMetadataCount = 0;
  failedCount = 0;
  cancelled = false;

  parseError = '';
  readonly maxIsbnCount = MAX_ISBN_COUNT;
  private readonly initializeSelectedLibraryEffect = effect(() => {
    const libraries = this.libraries;
    if (libraries.length === 0) {
      return;
    }

    if (this.dialogConfig.data?.libraryId) {
      this.selectedLibraryId = this.dialogConfig.data.libraryId;
      return;
    }

    if (this.selectedLibraryId == null && libraries[0].id !== undefined) {
      this.selectedLibraryId = libraries[0].id;
    }
  });

  get libraries(): Library[] {
    return this.libraryService.libraries();
  }

  onFileSelect(event: FileSelectEvent): void {
    const file = event.files?.[0];
    if (!file) return;

    if (file.size > MAX_FILE_SIZE_BYTES) {
      this.parseError = 'fileTooLarge';
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      const content = reader.result as string;
      this.parseContent(content, file.name);
    };
    reader.readAsText(file, 'UTF-8');
  }

  parsePastedText(): void {
    if (!this.pasteText.trim()) return;
    this.parseContent(this.pasteText, 'paste');
  }

  clearParsed(): void {
    this.entries = [];
    this.skipped = [];
    this.duplicatesRemoved = 0;
    this.parseError = '';
    this.pasteText = '';
  }

  canStartImport(): boolean {
    return this.phase === 'upload' && this.entries.length > 0 && !!this.selectedLibraryId;
  }

  async startImport(): Promise<void> {
    if (!this.canStartImport()) return;

    const existingIsbns = await firstValueFrom(this.appBooksApi.findExistingIsbns(
      this.selectedLibraryId!,
      this.entries.map(entry => entry.isbn),
    ));
    if (existingIsbns.size > 0) {
      const pending: IsbnEntry[] = [];
      for (const entry of this.entries) {
        if (existingIsbns.has(entry.isbn)) {
          this.skipped.push({value: entry.isbn, reason: 'alreadyExists'});
        } else {
          pending.push(entry);
        }
      }
      this.entries = pending;
    }

    this.phase = 'processing';
    this.processedCount = 0;
    this.createdCount = 0;
    this.noMetadataCount = 0;
    this.failedCount = 0;
    this.cancelled = false;

    for (const entry of this.entries) {
      if (this.cancelled) break;

      entry.status = 'looking-up';

      try {
        const metadata = await this.lookupIsbn(entry.isbn);
        if (this.cancelled) break;

        const request: CreatePhysicalBookRequest = {
          libraryId: this.selectedLibraryId!,
          isbn: entry.isbn,
          title: metadata?.title || undefined,
          authors: metadata?.authors?.length ? [...metadata.authors] : undefined,
          description: metadata?.description || undefined,
          publisher: metadata?.publisher || undefined,
          publishedDate: metadata?.publishedDate || undefined,
          language: metadata?.language || undefined,
          pageCount: metadata?.pageCount ?? undefined,
          categories: metadata?.categories?.length ? [...metadata.categories] : undefined,
          thumbnailUrl: metadata?.thumbnailUrl || undefined,
        };

        await this.createBook(request);

        if (metadata?.title) {
          entry.status = 'created';
          entry.title = metadata.title;
          this.createdCount++;
        } else {
          entry.status = 'created-no-metadata';
          this.noMetadataCount++;
        }
      } catch (err: unknown) {
        entry.status = 'failed';
        entry.error = err instanceof Error ? err.message : 'Unknown error';
        this.failedCount++;
      }

      this.processedCount++;

      if (!this.cancelled && entry !== this.entries.at(-1)) {
        await this.delay(DELAY_BETWEEN_REQUESTS_MS);
      }
    }

    this.phase = 'summary';
  }

  cancelImport(): void {
    this.cancelled = true;
  }

  close(): void {
    this.dynamicDialogRef.close();
  }

  get progressPercent(): number {
    return this.entries.length > 0 ? Math.round((this.processedCount / this.entries.length) * 100) : 0;
  }

  get selectedLibraryName(): string {
    return this.libraries.find(l => l.id === this.selectedLibraryId)?.name ?? '';
  }

  private parseContent(content: string, source: string): void {
    this.parseError = '';
    this.skipped = [];
    this.duplicatesRemoved = 0;

    // Strip BOM
    const cleaned = content.replace(/^\uFEFF/, '');
    const lines = cleaned.split(/\r?\n|\r/);

    let isbns: string[];
    const extension = source.split('.').pop()?.toLowerCase();

    if (extension === 'csv' || extension === 'tsv') {
      isbns = this.parseCsvLines(lines, extension === 'tsv' ? '\t' : undefined);
    } else if (source === 'paste') {
      // Pasted text: split by newlines, commas, semicolons, or spaces
      const tokens = cleaned.split(/[\n\r,;\s]+/);
      isbns = tokens.map(t => t.trim()).filter(t => t.length > 0);
    } else {
      // Plain text: one per line
      isbns = lines.map(l => l.trim()).filter(l => l.length > 0 && !l.startsWith('#'));
    }

    const validEntries: IsbnEntry[] = [];
    const seen = new Set<string>();

    for (const raw of isbns) {
      const normalized = this.normalizeIsbn(raw);

      if (!this.isValidIsbn(normalized)) {
        this.skipped.push({value: raw, reason: 'invalid'});
        continue;
      }

      if (seen.has(normalized)) {
        this.duplicatesRemoved++;
        continue;
      }

      seen.add(normalized);
      validEntries.push({isbn: normalized, status: 'pending'});
    }

    if (validEntries.length > MAX_ISBN_COUNT) {
      this.parseError = 'tooMany';
      this.entries = validEntries.slice(0, MAX_ISBN_COUNT);
    } else {
      this.entries = validEntries;
    }

    if (this.entries.length === 0 && this.skipped.length === 0) {
      this.parseError = 'empty';
    }
  }

  private parseCsvLines(lines: string[], delimiter?: string): string[] {
    if (lines.length === 0) return [];

    // Auto-detect delimiter if not provided
    if (!delimiter) {
      const firstLine = lines[0];
      if (firstLine.includes('\t')) delimiter = '\t';
      else if (firstLine.includes(';')) delimiter = ';';
      else delimiter = ',';
    }

    const headerFields = lines[0].split(delimiter).map(f => f.trim().replaceAll(/^["']|["']$/g, '').toLowerCase());
    let isbnColIndex = headerFields.findIndex(h => h === 'isbn' || h === 'isbn13' || h === 'isbn10');

    let startLine: number;
    if (isbnColIndex >= 0) {
      startLine = 1; // skip header
    } else {
      isbnColIndex = 0; // assume first column
      startLine = 0;
    }

    const result: string[] = [];
    for (let i = startLine; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue;

      const fields = line.split(delimiter);
      if (fields.length > isbnColIndex) {
        const value = fields[isbnColIndex].trim().replaceAll(/^["']|["']$/g, '');
        if (value) result.push(value);
      }
    }
    return result;
  }

  private normalizeIsbn(raw: string): string {
    // Strip hyphens, spaces, surrounding whitespace; preserve trailing X
    return raw.trim().replaceAll(/[-\s]/g, '').toUpperCase();
  }

  private isValidIsbn(isbn: string): boolean {
    if (/^\d{10}$/.test(isbn) || /^\d{9}X$/.test(isbn)) return true;
    if (/^\d{13}$/.test(isbn) && (isbn.startsWith('978') || isbn.startsWith('979'))) return true;
    return false;
  }

  private lookupIsbn(isbn: string): Promise<BookMetadata | null> {
    return new Promise((resolve) => {
      this.bookMetadataService.lookupByIsbn(isbn).subscribe({
        next: metadata => resolve(metadata),
        error: err => {
          // Retry once on potential rate limiting
          if (err?.status === 429) {
            setTimeout(() => {
              this.bookMetadataService.lookupByIsbn(isbn).subscribe({
                next: metadata => resolve(metadata),
                error: () => resolve(null), // Still create with ISBN only
              });
            }, RETRY_DELAY_MS);
          } else {
            resolve(null); // No metadata found, still create with ISBN
          }
        },
      });
    });
  }

  private createBook(request: CreatePhysicalBookRequest): Promise<void> {
    return new Promise((resolve, reject) => {
      this.bookService.createPhysicalBook(request).subscribe({
        next: () => resolve(),
        error: err => reject(err),
      });
    });
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
