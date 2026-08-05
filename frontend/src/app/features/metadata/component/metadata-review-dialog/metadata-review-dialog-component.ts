import {Component, computed, inject, OnInit, signal, ViewChild} from '@angular/core';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {FetchedProposal, MetadataTaskService} from '../../../book/service/metadata-task';
import {Book} from '../../../book/model/book.model';
import {ProgressSpinner} from 'primeng/progressspinner';
import {Button} from 'primeng/button';
import {Divider} from 'primeng/divider';
import {ProgressBar} from 'primeng/progressbar';
import {Tooltip} from 'primeng/tooltip';
import {MetadataProgressService} from '../../../../shared/service/metadata-progress.service';
import {MetadataPickerComponent} from '../book-metadata-center/metadata-picker/metadata-picker.component';
import {DecimalPipe} from '@angular/common';
import {AppBooksApiService} from '../../../book/service/app-books-api.service';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {finalize, lastValueFrom, switchMap} from 'rxjs';

@Component({
  selector: 'app-metadata-review-dialog-component',
  standalone: true,
  templateUrl: './metadata-review-dialog-component.html',
  styleUrls: ['./metadata-review-dialog-component.scss'],
  imports: [
    DecimalPipe,MetadataPickerComponent, ProgressSpinner, Button, Divider, ProgressBar, Tooltip],
})
export class MetadataReviewDialogComponent implements OnInit {

  @ViewChild(MetadataPickerComponent)
  pickerComponent!: MetadataPickerComponent;

  private readonly config = inject(DynamicDialogConfig);
  private readonly dialogRef = inject(DynamicDialogRef);
  private readonly metadataTaskService = inject(MetadataTaskService);
  private readonly appBooksApi = inject(AppBooksApiService);
  private readonly progressService = inject(MetadataProgressService);

  readonly proposals = signal<FetchedProposal[]>([]);
  readonly currentIndex = signal(0);
  /** True from the moment Accept & Save is pressed until both server calls have settled. */
  readonly saving = signal(false);
  private readonly proposalBooksQuery = injectQuery(() => {
    const ids = [...new Set(this.proposals().map(proposal => proposal.bookId))];
    return {
      queryKey: ['app-book-summaries', ...ids] as const,
      queryFn: () => lastValueFrom(this.appBooksApi.getBooksByIds(ids)),
      enabled: ids.length > 0,
    };
  });
  private readonly proposalBooksById = computed(() => new Map(
    (this.proposalBooksQuery.data() ?? []).map(book => [book.id, book]),
  ));
  readonly loading = computed(() => {
    const proposalCount = new Set(this.proposals().map(proposal => proposal.bookId)).size;
    return proposalCount === 0 || this.proposalBooksById().size !== proposalCount;
  });
  readonly currentBook = computed<Book | null>(() => {
    const proposal = this.proposals()[this.currentIndex()];
    if (!proposal) {
      return null;
    }

    return this.proposalBooksById().get(proposal.bookId) ?? null;
  });

  ngOnInit() {
    const taskId = this.config.data?.taskId;
    if (!taskId) {
      this.dialogRef.close();
      return;
    }

    this.metadataTaskService.getTaskWithProposals(taskId).subscribe({
      next: (task) => {
        this.proposals.set(task.proposals || []);
        this.currentIndex.set(0);
      },
      error: () => {
        this.dialogRef.close();
      },
    });
  }

  get currentProposal(): FetchedProposal | null {
    return this.proposals()[this.currentIndex()] ?? null;
  }

  /**
   * Accepting is two server calls that must happen in this order.
   *
   * The picker's PUT is what actually writes the accepted values; the ACCEPTED status POST is what
   * makes the server file the per-field provenance, and it does that by comparing the proposal against
   * what the book holds *now*. Fired concurrently — as this used to — the POST usually reads pre-PUT
   * metadata and attributes nothing, and in the other interleaving the PUT deletes the rows the POST
   * has just filed. So the POST waits for the PUT's response, which is also the point at which its
   * transaction has committed.
   *
   * A failed save short-circuits the `switchMap`: a proposal whose values never landed must not be
   * marked accepted.
   */
  onSave(): void {
    const currentProposal = this.currentProposal;
    // The picker is guarded as well as the proposal: the footer holding Accept & Save renders under
    // `@if (!loading())` while the picker renders under the narrower
    // `@if (currentProposal?.metadataJson; as proposed)`, so a proposal with a falsy metadataJson
    // shows the button with no picker behind it. Reading `saveMetadata()` off `undefined` throws
    // before the pipe exists, so `finalize` never runs and `saving()` stays true — leaving the button
    // `[disabled]` for the rest of the dialog's life.
    const picker = this.pickerComponent;
    if (!currentProposal || !picker || this.saving()) return;
    this.saving.set(true);
    picker.saveMetadata().pipe(
      switchMap(() => this.metadataTaskService.updateProposalStatus(
        currentProposal.taskId, currentProposal.proposalId, 'ACCEPTED')),
      finalize(() => this.saving.set(false)),
    ).subscribe({
      next: () => {
        if (this.isLast) {
          this.metadataTaskService.deleteTask(currentProposal.taskId).subscribe(() => {
            this.progressService.clearTask(currentProposal.taskId);
          });
        }
      },
      error: () => undefined,   // the picker has already surfaced the failure to the user
    });
  }

  onNext(): void {
    const nextIndex = this.currentIndex() + 1;
    if (nextIndex >= this.proposals().length) {
      this.dialogRef.close();
    } else {
      this.currentIndex.set(nextIndex);
    }
  }

  lockAllMetadata(): void {
    this.pickerComponent?.lockAll();
  }

  unlockAllMetadata(): void {
    this.pickerComponent?.unlockAll();
  }

  get isLast(): boolean {
    return this.currentIndex() === this.proposals().length - 1;
  }

  close(): void {
    this.dialogRef.close();
  }
}
