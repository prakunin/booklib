import {ChangeDetectorRef, Component, computed, inject, ViewChild, effect, signal} from '@angular/core';
import {FileSelectEvent, FileUpload, FileUploadHandlerEvent} from 'primeng/fileupload';
import {Button} from 'primeng/button';
import {FormsModule} from '@angular/forms';
import {MessageService} from 'primeng/api';
import {Select} from 'primeng/select';
import {Badge} from 'primeng/badge';
import {LibraryService} from '../../../features/book/service/library.service';
import {Library, LibraryPath} from '../../../features/book/model/library.model';
import {API_CONFIG} from '../../../core/config/api-config';
import {HttpClient, HttpEventType, HttpRequest} from '@angular/common/http';
import {Tooltip} from 'primeng/tooltip';
import {AppSettingsService} from '../../service/app-settings.service';
import {SelectButton} from 'primeng/selectbutton';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {ProgressBar} from 'primeng/progressbar';
import {InputText} from 'primeng/inputtext';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {InpxBook, InpxImportResult, InpxImportService, InpxSearchResult, InpxSource} from './inpx-import.service';
import {UserService} from '../../../features/settings/user-management/user.service';

interface UploadingFile {
  file: File;
  status: 'Pending' | 'Uploading' | 'Uploaded' | 'Failed';
  progress: number;
  errorMessage?: string;
}

type FileChooserCallback = () => void;
type FileRemoveCallback = (event: Event, index: number) => void;

@Component({
  selector: 'app-book-uploader',
  standalone: true,
  imports: [
    FileUpload,
    Button,
    FormsModule,
    Select,
    Badge,
    Tooltip,
    SelectButton,
    ProgressBar,
    InputText,
    TranslocoDirective
  ],
  templateUrl: './book-uploader.component.html',
  styleUrl: './book-uploader.component.scss'
})
export class BookUploaderComponent {
  @ViewChild(FileUpload) fileUpload!: FileUpload;

  files: UploadingFile[] = [];
  isUploading = signal(false);
  uploadCompleted = signal(false);
  isSearchingInpx = signal(false);
  inpxBooks = signal<InpxBook[]>([]);
  selectedInpxIds = signal<Set<string>>(new Set());
  inpxSearchResult = signal<InpxSearchResult | null>(null);
  inpxImportResult = signal<InpxImportResult | null>(null);
  _selectedLibrary: Library | null = null;
  selectedPath: LibraryPath | null = null;
  inpxSourceLibrary: Library | null = null;
  inpxPath = '';
  inpxArchivePath = '';
  inpxQuery = '';

  private readonly libraryService = inject(LibraryService);
  private readonly messageService = inject(MessageService);
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly http = inject(HttpClient);
  private readonly inpxImportService = inject(InpxImportService);
  private readonly userService = inject(UserService);
  private readonly ref = inject(DynamicDialogRef);
  private readonly t = inject(TranslocoService);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly libraries = this.libraryService.libraries;
  // An INPX library's rescan only reads its index and archives, so extracted loose files would
  // never surface there - it can be an import source, never an import destination.
  readonly destinationLibraries = computed(() => this.libraries().filter(library => library.sourceType !== 'INPX'));
  readonly inpxSourceLibraries = computed(() => this.libraries().filter(library => library.sourceType === 'INPX'));
  // A raw index path is unconstrained filesystem access, so the server accepts it from admins only.
  readonly canUseManualInpxPath = computed(() => this.userService.currentUser()?.permissions?.admin === true);
  maxFileSizeBytes?: number;
  maxFileSizeDisplay: string = '100 MB';
  stateOptions = [
    {label: this.t.translate('shared.bookUploader.destinationLibrary'), value: 'library'},
    {label: this.t.translate('shared.bookUploader.destinationBookdrop'), value: 'bookdrop'},
    {label: this.t.translate('shared.bookUploader.destinationInpx'), value: 'inpx'}
  ];
  value = 'library';
  private readonly selectSingleLibraryEffect = effect(() => {
    const libraries = this.destinationLibraries();
    if (libraries.length !== 1 || this.selectedLibrary) {
      return;
    }

    this.selectedLibrary = libraries[0];
  });

  private readonly selectSingleInpxSourceEffect = effect(() => {
    const sources = this.inpxSourceLibraries();
    if (sources.length !== 1 || this.inpxSourceLibrary) {
      return;
    }

    this.inpxSourceLibrary = sources[0];
  });

  private readonly loadSettingsEffect = effect(() => {
    const settings = this.appSettingsService.appSettings();
    if (!settings) return;
    const maxSizeMb = settings.maxFileUploadSizeInMb ?? 100;
    this.maxFileSizeBytes = maxSizeMb * 1024 * 1024;
    this.maxFileSizeDisplay = `${maxSizeMb} MB`;
  });

  get selectedLibrary(): Library | null {
    return this._selectedLibrary;
  }

  set selectedLibrary(library: Library | null) {
    this._selectedLibrary = library;

    if (library?.paths?.length === 1) {
      this.selectedPath = library.paths[0];
    }
  }

  hasPendingFiles(): boolean {
    return this.files.some(f => f.status === 'Pending');
  }

  filesPresent(): boolean {
    return this.files.length > 0;
  }

  choose(_event: Event, chooseCallback: FileChooserCallback): void {
    chooseCallback();
  }

  onClear(clearCallback: () => void): void {
    clearCallback();
    this.files = [];
    this.uploadCompleted.set(false);
  }


  onFilesSelect(event: FileSelectEvent): void {
    this.uploadCompleted.set(false);
    if (this.value === 'library' && (!this.selectedLibrary || !this.selectedPath)) {

      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('shared.bookUploader.toast.noDestinationSummary'),
        detail: this.t.translate('shared.bookUploader.toast.noDestinationDetail'),
        life: 5000
      });
      // We need to clear the files input explicitely, otherwise the files remain selected in the file upload component
      this.fileUpload.clear();
      return;
    }

    const newFiles = event.currentFiles;
    for (const file of newFiles) {
      const exists = this.files.some(f => f.file.name === file.name && f.file.size === file.size);
      if (exists) {
        continue;
      }

      if (this.maxFileSizeBytes && file.size > this.maxFileSizeBytes) {
        const errorMsg = `File exceeds maximum size of ${this.formatSize(this.maxFileSizeBytes)}`;
        this.files.unshift({
          file,
          status: 'Failed',
          progress: 0,
          errorMessage: errorMsg
        });
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('shared.bookUploader.toast.fileTooLargeSummary'),
          detail: this.t.translate('shared.bookUploader.toast.fileTooLargeDetail', {fileName: file.name, maxSize: this.formatSize(this.maxFileSizeBytes)}),
          life: 5000
        });
      } else {
        this.files.unshift({file, status: 'Pending', progress: 0});
      }
    }
  }

  onRemoveTemplatingFile(event: Event, file: File, removeFileCallback: FileRemoveCallback, _index?: number): void {
    void _index;
    // Remove from our tracking array
    this.files = this.files.filter(f => f.file !== file);

    // Find and remove from p-fileupload's internal array (index may differ from ours)
    const fileUploadIndex = this.fileUpload.files?.findIndex(f => f.name === file.name && f.size === file.size) ?? -1;
    if (fileUploadIndex >= 0) {
      removeFileCallback(event, fileUploadIndex);
    }
  }

  uploadEvent(uploadCallback: () => void): void {
    uploadCallback();
  }

  uploadFiles(_event: FileUploadHandlerEvent): void {
    void _event;
    if (this.value === 'library' && (!this.selectedLibrary || !this.selectedPath)) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('shared.bookUploader.toast.missingDataSummary'),
        detail: this.t.translate('shared.bookUploader.toast.missingDataDetail'),
        life: 4000
      });
      return;
    }

    const filesToUpload = this.files.filter(f => f.status === 'Pending');
    if (filesToUpload.length === 0) return;

    this.isUploading.set(true);
    this.uploadCompleted.set(false);
    const destination = this.value;
    const libraryId = this.selectedLibrary?.id?.toString();
    const pathId = this.selectedPath?.id?.toString();

    this.uploadBatch(filesToUpload, 0, 1, destination, libraryId, pathId);
  }

  /** The chosen INPX library, or an admin's manually entered path when no library is selected. */
  inpxSource(): InpxSource | null {
    if (this.inpxSourceLibrary?.id != null) {
      return {sourceLibraryId: this.inpxSourceLibrary.id};
    }
    if (this.canUseManualInpxPath() && this.inpxPath.trim()) {
      return {inpxPath: this.inpxPath.trim(), archivePath: this.inpxArchivePath.trim() || null};
    }
    return null;
  }

  searchInpx(): void {
    const source = this.inpxSource();
    if (!source) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('shared.bookUploader.toast.missingDataSummary'),
        detail: this.t.translate('shared.bookUploader.inpxPathRequired')
      });
      return;
    }

    this.isSearchingInpx.set(true);
    this.inpxImportResult.set(null);
    this.inpxImportService.search(source, this.inpxQuery.trim()).subscribe({
      next: result => {
        this.inpxBooks.set(result.books);
        this.inpxSearchResult.set(result);
        this.selectedInpxIds.set(new Set());
        this.isSearchingInpx.set(false);
      },
      error: error => {
        this.isSearchingInpx.set(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('shared.bookUploader.inpxSearchFailed'),
          detail: error?.error?.message ?? this.t.translate('shared.bookUploader.inpxSearchFailedDetail')
        });
      }
    });
  }

  toggleInpxBook(bookId: string, selected: boolean): void {
    this.selectedInpxIds.update(current => {
      const next = new Set(current);
      if (selected) {
        next.add(bookId);
      } else {
        next.delete(bookId);
      }
      return next;
    });
  }

  toggleAllInpxBooks(selected: boolean): void {
    this.selectedInpxIds.set(selected ? new Set(this.inpxBooks().map(book => book.id)) : new Set());
  }

  isInpxBookSelected(bookId: string): boolean {
    return this.selectedInpxIds().has(bookId);
  }

  importSelectedInpxBooks(): void {
    const source = this.inpxSource();
    if (!this.selectedLibrary?.id || !this.selectedPath?.id || this.selectedInpxIds().size === 0 || !source) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('shared.bookUploader.toast.missingDataSummary'),
        detail: this.t.translate('shared.bookUploader.inpxSelectionRequired')
      });
      return;
    }

    const selectedIds = this.selectedInpxIds();
    const books = this.inpxBooks()
      .filter(book => selectedIds.has(book.id))
      .map(book => ({
        archiveName: book.archiveName,
        fileName: book.fileName,
        extension: book.extension
      }));

    this.isUploading.set(true);
    this.inpxImportResult.set(null);
    this.inpxImportService.importBooks(this.selectedLibrary.id, {
      ...source,
      libraryPathId: this.selectedPath.id,
      books
    }).subscribe({
      next: result => {
        this.isUploading.set(false);
        this.inpxImportResult.set(result);
        this.messageService.add({
          severity: result.failed > 0 ? 'warn' : 'success',
          summary: this.t.translate('shared.bookUploader.inpxImportComplete'),
          detail: this.t.translate('shared.bookUploader.inpxImportSummary', result)
        });
      },
      error: error => {
        this.isUploading.set(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('shared.bookUploader.inpxImportFailed'),
          detail: error?.error?.message ?? this.t.translate('shared.bookUploader.inpxImportFailedDetail')
        });
      }
    });
  }

  private uploadBatch(files: UploadingFile[], startIndex: number, batchSize: number, destination: string, libraryId?: string, pathId?: string): void {
    const batch = files.slice(startIndex, startIndex + batchSize);
    if (batch.length === 0) {
      this.isUploading.set(false);
      this.uploadCompleted.set(true);

      if (destination === 'bookdrop') {
        this.ref.close('uploaded_to_bookdrop');
      }
      return;
    }

    let pending = batch.length;

    for (const uploadFile of batch) {
      uploadFile.status = 'Uploading';
      uploadFile.progress = 0;

      const formData = new FormData();
      const cleanFile = new File([uploadFile.file], uploadFile.file.name, {type: uploadFile.file.type});
      formData.append('file', cleanFile, uploadFile.file.name);

      let uploadUrl: string;
      if (destination === 'library') {
        if (libraryId && pathId) {
          formData.append('libraryId', libraryId);
          formData.append('pathId', pathId);
        }
        uploadUrl = `${API_CONFIG.BASE_URL}/api/v1/files/upload`;
      } else {
        uploadUrl = `${API_CONFIG.BASE_URL}/api/v1/files/upload/bookdrop`;
      }

      const req = new HttpRequest('POST', uploadUrl, formData, {
        reportProgress: true
      });

      this.http.request(req).subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress) {
            if (event.total) {
              uploadFile.progress = Math.round((event.loaded / event.total) * 100);
            }
            this.cdr.detectChanges();
          } else if (event.type === HttpEventType.Response) {
            uploadFile.status = 'Uploaded';
            uploadFile.progress = 100;
            this.cdr.detectChanges();
            if (--pending === 0) {
              setTimeout(() => {
                this.uploadBatch(files, startIndex + batchSize, batchSize, destination, libraryId, pathId);
              }, 1000);
            }
          }
        },
        error: (err) => {
          uploadFile.status = 'Failed';
          uploadFile.progress = 0;
          uploadFile.errorMessage = err?.error?.message || this.t.translate('shared.bookUploader.toast.uploadFailedDefault');
          this.cdr.detectChanges();
          if (--pending === 0) {
            setTimeout(() => {
              this.uploadBatch(files, startIndex + batchSize, batchSize, destination, libraryId, pathId);
            }, 1000);
          }
        }
      });
    }
  }

  isChooseDisabled(): boolean {
    if (this.value === 'bookdrop') {
      return this.isUploading();
    }
    return !this.selectedLibrary || !this.selectedPath || this.isUploading();
  }

  isUploadDisabled(): boolean {
    return this.isChooseDisabled() || !this.filesPresent() || !this.hasPendingFiles();
  }

  isUploadZoneActive(): boolean {
    if (this.value === 'bookdrop') {
      return true;
    }
    return !!(this.selectedLibrary && this.selectedPath);
  }

  formatSize(bytes: number): string {
    const k = 1024;
    const dm = 2;
    if (bytes < k) return `${bytes} B`;
    if (bytes < k * k) return `${(bytes / k).toFixed(dm)} KB`;
    return `${(bytes / (k * k)).toFixed(dm)} MB`;
  }

  getBadgeSeverity(status: UploadingFile['status']): 'info' | 'warn' | 'success' | 'danger' {
    switch (status) {
      case 'Pending':
        return 'warn';
      case 'Uploading':
        return 'info';
      case 'Uploaded':
        return 'success';
      case 'Failed':
        return 'danger';
      default:
        return 'info';
    }
  }

  getFileStatusLabel(uploadFile: UploadingFile): string {
    if (uploadFile.status === 'Failed' && uploadFile.errorMessage?.includes('exceeds maximum size')) {
      return this.t.translate('shared.bookUploader.statusTooLarge');
    }
    switch (uploadFile.status) {
      case 'Pending':
        return this.t.translate('shared.bookUploader.statusReady');
      case 'Uploading':
        return this.t.translate('shared.bookUploader.statusUploading');
      case 'Uploaded':
        return this.t.translate('shared.bookUploader.statusUploaded');
      case 'Failed':
        return this.t.translate('shared.bookUploader.statusFailed');
      default:
        return uploadFile.status;
    }
  }

  hasUploadCompleted(): boolean {
    return this.uploadCompleted();
  }

  closeDialog(): void {
    this.ref.close();
  }

  getOverallProgress(): number {
    if (this.files.length === 0) return 0;
    const totalProgress = this.files.reduce((sum, f) => sum + f.progress, 0);
    return Math.round(totalProgress / this.files.length);
  }

  getUploadedCount(): number {
    return this.files.filter(f => f.status === 'Uploaded').length;
  }

  getFailedCount(): number {
    return this.files.filter(f => f.status === 'Failed').length;
  }

  getUploadingCount(): number {
    return this.files.filter(f => f.status === 'Uploading').length;
  }

  getTotalBytes(): number {
    return this.files.reduce((sum, f) => sum + f.file.size, 0);
  }

  getUploadedBytes(): number {
    return this.files
      .filter(f => f.status === 'Uploaded')
      .reduce((sum, f) => sum + f.file.size, 0);
  }

}
