import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { DynamicDialogConfig, DynamicDialogRef } from 'primeng/dynamicdialog';
import { MessageService } from 'primeng/api';
import { Router } from '@angular/router';
import { LibraryService } from '../book/service/library.service';
import { FormsModule } from '@angular/forms';
import { InputText } from 'primeng/inputtext';
import { Library, LibrarySourceType, MetadataSource, OrganizationMode } from '../book/model/library.model';
import { BookType } from '../book/model/book.model';
import { ToggleSwitch } from 'primeng/toggleswitch';
import { Tooltip } from 'primeng/tooltip';
import { IconPickerService } from '../../shared/service/icon-picker.service';
import { Button } from 'primeng/button';
import { IconDisplayComponent } from '../../shared/components/icon-display/icon-display.component';
import { DialogLauncherService } from '../../shared/services/dialog-launcher.service';
import { switchMap, map, tap, catchError, of, Subject, EMPTY } from 'rxjs';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { Checkbox } from 'primeng/checkbox';
import { Select } from 'primeng/select';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LibraryImportProgressService } from '../../shared/service/library-import-progress.service';
import { IconSelection, toIconSelection } from '../../shared/icons/icon-selection';
import { InpxIndexOption, UtilityService } from '../../shared/components/directory-picker/utility.service';

interface FormatEntry { type: BookType; label: string }

interface LibraryCreatorDialogData {
  mode: 'create' | 'edit';
  libraryId?: number;
}

@Component({
  selector: 'app-library-creator',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './library-creator.component.html',
  imports: [FormsModule, InputText, ToggleSwitch, Tooltip, Button, IconDisplayComponent, DragDropModule, Checkbox, Select, TranslocoDirective, TranslocoPipe],
  styleUrl: './library-creator.component.scss'
})
export class LibraryCreatorComponent {

  private readonly dialogLauncherService = inject(DialogLauncherService);
  private readonly dynamicDialogRef = inject(DynamicDialogRef);
  private readonly dynamicDialogConfig = inject(DynamicDialogConfig);
  private readonly libraryService = inject(LibraryService);
  private readonly messageService = inject(MessageService);
  private readonly router = inject(Router);
  private readonly iconPicker = inject(IconPickerService);
  private readonly t = inject(TranslocoService);
  private readonly libraryImportProgressService = inject(LibraryImportProgressService);
  private readonly utilityService = inject(UtilityService);

  private readonly activeLang = toSignal(this.t.langChanges$, {
    initialValue: this.t.getActiveLang(),
  });

  readonly allBookFormats: FormatEntry[] = [
    { type: 'EPUB', label: 'EPUB' },
    { type: 'PDF', label: 'PDF' },
    { type: 'CBX', label: 'CBX (CBZ/CBR/CB7)' },
    { type: 'MOBI', label: 'MOBI' },
    { type: 'AZW3', label: 'AZW3' },
    { type: 'FB2', label: 'FB2' },
    { type: 'AUDIOBOOK', label: 'Audiobook' },
  ];

  readonly chosenLibraryName = signal<string>('');
  readonly folders = signal<string[]>([]);
  readonly selectedIcon = signal<IconSelection | null>(null);
  readonly mode = signal<string>('');
  readonly editModeLibraryName = signal<string>('');
  readonly watch = signal<boolean>(false);
  readonly formatPriority = signal<FormatEntry[]>([...this.allBookFormats]);
  readonly allowAllFormats = signal<boolean>(true);
  readonly selectedAllowedFormats = signal<Set<BookType>>(new Set(this.allBookFormats.map(f => f.type)));
  readonly formatCounts = signal<Record<string, number>>({});
  readonly metadataSource = signal<MetadataSource>('EMBEDDED');
  readonly organizationMode = signal<OrganizationMode>('BOOK_PER_FILE');
  readonly sourceType = signal<LibrarySourceType>('FILESYSTEM');
  readonly inpxPath = signal<string>('');
  readonly inpxIndexOptions = signal<InpxIndexOption[]>([]);
  readonly isDetectingInpx = signal<boolean>(false);
  readonly inpxManualEntry = signal<boolean>(false);
  readonly inpxDetectionRan = signal<boolean>(false);
  readonly inpxDetectFailed = signal<boolean>(false);

  private readonly inpxFolderRequested = new Subject<string | null>();
  /** Tracks the last value this discovery flow auto-filled into `inpxPath`, so a
   *  failed/empty detection only clears a path it put there — never one the user
   *  typed or that was loaded from an existing library in edit mode. */
  private readonly lastAutoAppliedInpxPath = signal<string | null>(null);

  readonly sourceTypeOptions = computed(() => {
    this.activeLang();
    return [
      { label: this.t.translate('libraryCreator.creator.sourceTypeFilesystem'), value: 'FILESYSTEM' },
      { label: this.t.translate('libraryCreator.creator.sourceTypeInpx'), value: 'INPX' },
    ];
  });

  readonly showInpxIndexSelect = computed(() =>
    !this.inpxManualEntry() && this.inpxIndexOptions().length > 1);

  readonly inpxIndexSelectOptions = computed(() => this.inpxIndexOptions().map(option => ({
    label: `${option.fileName} — ${this.formatIndexSize(option.sizeBytes)}`,
    value: option.path,
  })));

  formatIndexSize(sizeBytes: number): string {
    if (sizeBytes < 1024) {
      return `${sizeBytes} B`;
    }
    if (sizeBytes < 1024 * 1024) {
      return `${(sizeBytes / 1024).toFixed(1)} KB`;
    }
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`;
  }

  readonly metadataSourceOptions = computed(() => {
    this.activeLang();
    return [
      { label: this.t.translate('libraryCreator.creator.metadataSourceEmbedded'), value: 'EMBEDDED' },
      { label: this.t.translate('libraryCreator.creator.metadataSourceSidecar'), value: 'SIDECAR' },
      { label: this.t.translate('libraryCreator.creator.metadataSourcePreferSidecar'), value: 'PREFER_SIDECAR' },
      { label: this.t.translate('libraryCreator.creator.metadataSourcePreferEmbedded'), value: 'PREFER_EMBEDDED' },
      { label: this.t.translate('libraryCreator.creator.metadataSourceNone'), value: 'NONE' },
    ];
  });

  readonly organizationModeOptions = computed(() => {
    this.activeLang();
    const base = [
      { label: this.t.translate('libraryCreator.creator.organizationModeBookPerFile'), value: 'BOOK_PER_FILE' },
      { label: this.t.translate('libraryCreator.creator.organizationModeBookPerFolder'), value: 'BOOK_PER_FOLDER' },
    ];
    if (this.organizationMode() === 'AUTO_DETECT') {
      base.push({ label: this.t.translate('libraryCreator.creator.organizationModeAutoDetect'), value: 'AUTO_DETECT' });
    }
    return base;
  });

  readonly isLibraryDetailsValid = computed(() => !!this.chosenLibraryName().trim());
  readonly isDirectorySelectionValid = computed(() => this.sourceType() === 'INPX'
    ? this.folders().length === 1
    : this.folders().length > 0);

  constructor() {
    /**
     * Runs once after construction. `untracked` prevents the body from being
     * treated as a reactive dependency (we only want it to run once on init).
     */
    effect(() => {
      untracked(() => this.initFromDialogData());
    });

    this.inpxFolderRequested.pipe(
      switchMap(folder => {
        // A null folder cancels whatever request is in flight without starting a new one.
        if (folder === null) {
          return EMPTY;
        }
        this.isDetectingInpx.set(true);
        this.inpxDetectFailed.set(false);
        return this.utilityService.getInpxFiles(folder).pipe(
          map(options => ({ options, failed: false as const })),
          catchError(() => of({ options: [] as InpxIndexOption[], failed: true as const })),
        );
      }),
      takeUntilDestroyed(),
    ).subscribe(({ options, failed }) => this.applyInpxDetectionResult(options, failed));
  }

  private initFromDialogData(): void {
    const data = this.dynamicDialogConfig?.data as LibraryCreatorDialogData;
    if (data?.mode !== 'edit') {
      this.mode.set('create');
      return;
    }

    this.mode.set('edit');

    const library = this.libraryService.findLibraryById(data.libraryId!);
    if (!library) {
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('libraryCreator.creator.toast.updateFailedSummary'),
        detail: this.t.translate('libraryCreator.creator.toast.updateFailedDetail'),
      });
      this.dynamicDialogRef.close();
      return;
    }

    const { name, icon, iconType, paths, watch, formatPriority, allowedFormats } = library;

    this.chosenLibraryName.set(name);
    this.editModeLibraryName.set(name);
    this.watch.set(watch);
    this.folders.set(paths.map(p => p.path));
    this.sourceType.set(library.sourceType ?? 'FILESYSTEM');
    this.inpxPath.set(library.inpxPath ?? '');

    if (icon != null && iconType) {
      this.selectedIcon.set(toIconSelection(icon, iconType));
    }

    if (formatPriority && formatPriority.length > 0) {
      const ordered = formatPriority
        .map(type => this.allBookFormats.find(f => f.type === type)!)
        .filter(Boolean);
      const existingTypes = new Set(formatPriority);
      this.allBookFormats.forEach(f => {
        if (!existingTypes.has(f.type)) ordered.push(f);
      });
      this.formatPriority.set(ordered);
    }

    if (allowedFormats && allowedFormats.length > 0) {
      this.allowAllFormats.set(false);
      this.selectedAllowedFormats.set(new Set(allowedFormats));
    } else {
      this.allowAllFormats.set(true);
      this.selectedAllowedFormats.set(new Set(this.allBookFormats.map(f => f.type)));
    }

    if (library.metadataSource) {
      this.metadataSource.set(library.metadataSource);
    }

    if (library.organizationMode) {
      this.organizationMode.set(library.organizationMode);
    }

    this.libraryService.getBookCountsByFormat(library.id!).subscribe(counts => {
      this.formatCounts.set(counts);
    });
  }

  onAllowAllFormatsChange(): void {
    if (this.allowAllFormats()) {
      this.selectedAllowedFormats.set(new Set(this.allBookFormats.map(f => f.type)));
    }
  }

  onFormatCheckboxChange(formatType: BookType, checked: boolean): void {
    const next = new Set(this.selectedAllowedFormats());
    if (checked) {
      next.add(formatType);
    } else {
      if (next.size === 1 && next.has(formatType)) {
        return;
      }
      next.delete(formatType);
    }
    this.selectedAllowedFormats.set(next);
    this.allowAllFormats.set(next.size === this.allBookFormats.length);
  }

  isFormatSelected(formatType: BookType): boolean {
    return this.selectedAllowedFormats().has(formatType);
  }

  getFormatWarning(formatType: BookType): string | null {
    if (this.mode() !== 'edit') return null;
    const count = this.formatCounts()[formatType];
    if (count && count > 0 && !this.selectedAllowedFormats().has(formatType)) {
      return this.t.translate('libraryCreator.creator.formatWarning', { count });
    }
    return null;
  }

  hasAnyFormatWarning(): boolean {
    if (this.mode() !== 'edit') return false;
    return this.allBookFormats.some(f => this.getFormatWarning(f.type) !== null);
  }

  async openDirectoryPicker(): Promise<void> {
    const ref = await this.dialogLauncherService.openDirectoryPickerDialog().catch(() => null);
    ref?.onClose.subscribe((selectedFolders: string[] | null) => {
      if (selectedFolders && selectedFolders.length > 0) {
        if (this.sourceType() === 'INPX') {
          this.folders.set([selectedFolders[0]]);
          this.detectInpxIndexes(selectedFolders[0]);
          return;
        }
        this.folders.update(current => {
          const incoming = selectedFolders.filter(f => !current.includes(f));
          return incoming.length > 0 ? [...current, ...incoming] : current;
        });
      }
    });
  }

  private detectInpxIndexes(folder: string): void {
    this.inpxFolderRequested.next(folder);
  }

  private applyInpxDetectionResult(options: InpxIndexOption[], failed: boolean): void {
    this.isDetectingInpx.set(false);
    this.inpxDetectionRan.set(true);
    this.inpxDetectFailed.set(failed);
    this.inpxIndexOptions.set(options);

    if (failed || options.length === 0) {
      this.inpxManualEntry.set(true);
      if (this.inpxPath() === this.lastAutoAppliedInpxPath()) {
        this.inpxPath.set('');
        this.lastAutoAppliedInpxPath.set(null);
      }
      return;
    }

    this.inpxManualEntry.set(false);
    if (options.length === 1) {
      this.inpxPath.set(options[0].path);
      this.lastAutoAppliedInpxPath.set(options[0].path);
    } else if (!options.some(option => option.path === this.inpxPath())) {
      if (this.inpxPath() === this.lastAutoAppliedInpxPath()) {
        this.inpxPath.set('');
        this.lastAutoAppliedInpxPath.set(null);
      }
    }
  }

  onSourceTypeChange(type: LibrarySourceType): void {
    this.sourceType.set(type);
    this.watch.set(false);
    this.folders.set([]);
    this.resetInpxDiscoveryState();
  }

  private resetInpxDiscoveryState(): void {
    this.inpxFolderRequested.next(null);
    this.isDetectingInpx.set(false);
    this.inpxPath.set('');
    this.inpxIndexOptions.set([]);
    this.inpxManualEntry.set(false);
    this.inpxDetectionRan.set(false);
    this.inpxDetectFailed.set(false);
    this.lastAutoAppliedInpxPath.set(null);
  }

  useManualInpxEntry(): void {
    this.inpxManualEntry.set(true);
  }

  useDetectedInpxIndexes(): void {
    this.inpxManualEntry.set(false);
  }

  addFolder(folder: string): void {
    this.folders.update(current => [...current, folder]);
  }

  removeFolder(index: number): void {
    this.folders.update(current => current.filter((_, i) => i !== index));
    if (this.sourceType() === 'INPX') {
      this.resetInpxDiscoveryState();
    }
  }

  openIconPicker(): void {
    this.iconPicker.open().subscribe(icon => {
      if (icon) this.selectedIcon.set(icon);
    });
  }

  clearSelectedIcon(): void {
    this.selectedIcon.set(null);
  }

  closeDialog(): void {
    this.dynamicDialogRef.close();
  }

  createOrUpdateLibrary(): void {
    const trimmedLibraryName = this.chosenLibraryName().trim();
    if (trimmedLibraryName && trimmedLibraryName !== this.editModeLibraryName()) {
      const exists = this.libraryService.doesLibraryExistByName(trimmedLibraryName);
      if (exists) {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('libraryCreator.creator.toast.nameExistsSummary'),
          detail: this.t.translate('libraryCreator.creator.toast.nameExistsDetail'),
        });
        return;
      }
    }

    const library: Library = {
      name: trimmedLibraryName,
      icon: this.selectedIcon()?.value ?? null,
      iconType: this.selectedIcon()?.type ?? null,
      paths: this.folders().map(folder => ({ path: folder })),
      watch: this.watch(),
      sourceType: this.sourceType(),
      inpxPath: this.sourceType() === 'INPX' ? (this.inpxPath().trim() || null) : null,
      inpxArchivePath: this.sourceType() === 'INPX' ? this.folders()[0] : null,
      formatPriority: this.formatPriority().map(f => f.type),
      allowedFormats: this.allowAllFormats() ? [] : Array.from(this.selectedAllowedFormats()),
      metadataSource: this.metadataSource(),
      organizationMode: this.organizationMode(),
    };

    if (this.mode() === 'edit') {
      const libraryId = (this.dynamicDialogConfig.data as LibraryCreatorDialogData)?.libraryId;
      if (libraryId == null) {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('libraryCreator.creator.toast.updateFailedSummary'),
          detail: this.t.translate('libraryCreator.creator.toast.updateFailedDetail'),
        });
        return;
      }

      this.libraryService.updateLibrary(library, libraryId).pipe(
        switchMap(() => this.libraryService.refreshLibrary(libraryId))
      ).subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: this.t.translate('libraryCreator.creator.toast.updatedSummary'), detail: this.t.translate('libraryCreator.creator.toast.updatedDetail') });
          this.dynamicDialogRef.close();
        },
        error: (e: HttpErrorResponse) => {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('libraryCreator.creator.toast.updateFailedSummary'),
            detail: this.extractServerErrorMessage(e) ?? this.t.translate('libraryCreator.creator.toast.updateFailedDetail'),
          });
          console.error(e);
        }
      });
    } else {
      const preflight$ = this.sourceType() === 'FILESYSTEM'
        ? this.libraryService.scanLibraryPaths(library).pipe(
          tap(count => {
            if (count >= 500) {
              this.libraryImportProgressService.start(library.name, count);
            }
          })
        )
        : of(0);

      preflight$.pipe(
        switchMap(count =>
          this.libraryService.createLibrary(library).pipe(
            map(createdLibrary => ({ createdLibrary, count }))
          )
        )
      ).subscribe({
        next: ({ createdLibrary, count }) => {
          if (createdLibrary) {
            if (count >= 500 && createdLibrary.id !== undefined) {
              this.libraryImportProgressService.attachLibrary(createdLibrary.id);
            }
            this.router.navigate(['/library', createdLibrary.id, 'books']);
            this.messageService.add({
              severity: 'success',
              summary: this.t.translate('libraryCreator.creator.toast.createdSummary'),
              detail: count >= 500
                ? this.t.translate('libraryCreator.creator.toast.createdLargeDetail', { count })
                : this.t.translate('libraryCreator.creator.toast.createdDetail'),
            });
            this.dynamicDialogRef.close();
          }
        },
        error: (e: HttpErrorResponse) => {
          this.libraryImportProgressService.fail();
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('libraryCreator.creator.toast.createFailedSummary'),
            detail: this.extractServerErrorMessage(e) ?? this.t.translate('libraryCreator.creator.toast.createFailedDetail'),
          });
          console.error(e);
        }
      });
    }
  }

  /**
   * Extracts the backend's `message` field from an error response body, if present.
   * The body may be absent, a plain string, or an object without a `message` property,
   * so every step is checked defensively before trusting the value.
   */
  private extractServerErrorMessage(error: HttpErrorResponse): string | null {
    const body: unknown = error.error;
    if (body && typeof body === 'object' && 'message' in body) {
      const message = (body as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim()) {
        return message;
      }
    }
    return null;
  }

  onFormatPriorityDrop(event: CdkDragDrop<FormatEntry[]>): void {
    this.formatPriority.update(current => {
      const next = [...current];
      moveItemInArray(next, event.previousIndex, event.currentIndex);
      return next;
    });
  }
}
