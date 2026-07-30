import {Component, computed, inject, OnDestroy, OnInit, signal} from '@angular/core';
import {Button} from 'primeng/button';
import {Checkbox} from 'primeng/checkbox';
import {FormsModule} from '@angular/forms';
import {ProgressSpinner} from 'primeng/progressspinner';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {Subscription} from 'rxjs';
import {SmartEnrichmentService} from '../../service/smart-enrichment.service';
import {
  MetadataFieldProposal,
  RatingVerification,
  ResolvedWorkIdentity,
  SmartEnrichmentApplyMode,
  SmartEnrichmentStage
} from '../../model/smart-enrichment.model';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';
import {BookMetadata} from '../../../book/model/book.model';

/** The backend joins list-valued proposals (genres) with ", "; this reverses that for the form. */
function splitProposedList(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter((item) => item.length > 0);
}

/**
 * Shows what an enrichment run found and lets the user apply it field by field.
 *
 * Nothing is written without an explicit click. That is the whole design of the first version: a
 * wrong match has to be a visible mistake the user declines, not a silent overwrite discovered
 * months later.
 */
@Component({
  selector: 'app-smart-enrichment',
  standalone: true,
  imports: [Button, Checkbox, FormsModule, ProgressSpinner, TranslocoDirective],
  templateUrl: './smart-enrichment.component.html',
  styleUrl: './smart-enrichment.component.scss',
})
export class SmartEnrichmentComponent implements OnInit, OnDestroy {
  private readonly config = inject(DynamicDialogConfig);
  private readonly dialogRef = inject(DynamicDialogRef);
  private readonly smartEnrichmentService = inject(SmartEnrichmentService);
  private readonly metadataManageService = inject(BookMetadataManageService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  private subscription?: Subscription;

  readonly bookId: number = this.config.data.bookId;
  /**
   * Opened from the editor, the dialog hands the values back instead of writing them: the user is
   * already looking at the form and expects to see the change before it is saved.
   */
  readonly applyMode: SmartEnrichmentApplyMode = this.config.data.applyMode ?? 'save';
  readonly stage = signal<SmartEnrichmentStage>('RESOLVING');
  readonly identity = signal<ResolvedWorkIdentity | null>(null);
  readonly ratingVerification = signal<RatingVerification | null>(null);
  readonly proposals = signal<MetadataFieldProposal[]>([]);
  readonly errorMessage = signal<string | null>(null);
  readonly applying = signal(false);
  readonly selectedFields = signal<Set<string>>(new Set());

  readonly running = computed(() => this.stage() === 'RESOLVING' || this.stage() === 'VERIFYING');
  readonly applicableProposals = computed(() => this.proposals().filter((proposal) => !proposal.locked));
  readonly canApply = computed(() => !this.applying() && this.selectedFields().size > 0);

  ngOnInit(): void {
    this.subscription = this.smartEnrichmentService.enrich(this.bookId).subscribe({
      next: (event) => {
        this.stage.set(event.stage);
        if (event.identity) {
          this.identity.set(event.identity);
        }
        if (event.ratingVerification) {
          this.ratingVerification.set(event.ratingVerification);
        }
        if (event.stage === 'COMPLETED') {
          this.proposals.set(event.proposals);
          // Unlocked proposals start selected: the common case is accepting all of them, and the
          // user is looking at every value before the apply button is ever pressed.
          this.selectedFields.set(new Set(event.proposals.filter((p) => !p.locked).map((p) => p.field)));
        }
        if (event.stage === 'FAILED') {
          this.errorMessage.set(event.message);
        }
      },
      error: (error) => {
        this.stage.set('FAILED');
        this.errorMessage.set(error?.message ?? this.t.translate('metadata.smartEnrichment.genericError'));
      },
    });
  }

  ngOnDestroy(): void {
    // Aborts the request: a run left going after the dialog closes would hold an agent process
    // open for minutes with nowhere to deliver its answer.
    this.subscription?.unsubscribe();
  }

  isSelected(field: string): boolean {
    return this.selectedFields().has(field);
  }

  toggleField(field: string): void {
    const next = new Set(this.selectedFields());
    if (!next.delete(field)) {
      next.add(field);
    }
    this.selectedFields.set(next);
  }

  apply(): void {
    const selected = this.applicableProposals().filter((proposal) => this.isSelected(proposal.field));
    if (selected.length === 0) {
      return;
    }
    if (this.applyMode === 'form') {
      this.dialogRef.close({proposals: selected});
      return;
    }
    this.applying.set(true);

    const metadata: Partial<BookMetadata> = {bookId: this.bookId};
    for (const proposal of selected) {
      this.assign(metadata, proposal);
    }

    // REPLACE_WHEN_PROVIDED so the fields the user did not tick keep their current values instead
    // of being cleared by an update that omits them.
    this.metadataManageService
      .updateBookMetadata(this.bookId, {metadata: metadata as BookMetadata, clearFlags: {}}, false, 'REPLACE_WHEN_PROVIDED')
      .subscribe({
        next: () => {
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('metadata.smartEnrichment.appliedSummary'),
            detail: this.t.translate('metadata.smartEnrichment.appliedDetail', {count: selected.length}),
          });
          this.dialogRef.close(true);
        },
        error: (error) => {
          this.applying.set(false);
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('metadata.smartEnrichment.applyErrorSummary'),
            detail: error?.error?.message ?? this.t.translate('metadata.smartEnrichment.genericError'),
          });
        },
      });
  }

  close(): void {
    this.dialogRef.close(false);
  }

  private assign(metadata: Partial<BookMetadata>, proposal: MetadataFieldProposal): void {
    switch (proposal.field) {
      case 'title':
        metadata.title = proposal.proposedValue;
        break;
      case 'authors':
        metadata.authors = [proposal.proposedValue];
        break;
      case 'language':
        metadata.language = proposal.proposedValue;
        break;
      case 'description':
        metadata.description = proposal.proposedValue;
        break;
      case 'publisher':
        metadata.publisher = proposal.proposedValue;
        break;
      case 'publishedDate':
        metadata.publishedDate = proposal.proposedValue;
        break;
      case 'isbn13':
        metadata.isbn13 = proposal.proposedValue;
        break;
      case 'isbn10':
        metadata.isbn10 = proposal.proposedValue;
        break;
      case 'pageCount':
        metadata.pageCount = Number(proposal.proposedValue);
        break;
      case 'seriesName':
        metadata.seriesName = proposal.proposedValue;
        break;
      case 'seriesNumber':
        metadata.seriesNumber = Number(proposal.proposedValue);
        break;
      case 'seriesTotal':
        metadata.seriesTotal = Number(proposal.proposedValue);
        break;
      case 'categories':
        metadata.categories = splitProposedList(proposal.proposedValue);
        break;
      case 'goodreadsId':
        metadata.goodreadsId = proposal.proposedValue;
        break;
      case 'goodreadsRating':
        metadata.goodreadsRating = Number(proposal.proposedValue);
        break;
      default:
        // A field the backend proposes but this build does not know how to write is dropped rather
        // than guessed at.
        break;
    }
  }
}
