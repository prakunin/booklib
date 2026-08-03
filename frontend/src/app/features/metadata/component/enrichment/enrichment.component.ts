import {Component, computed, inject, signal} from '@angular/core';
import {Button} from 'primeng/button';
import {Checkbox} from 'primeng/checkbox';
import {FormsModule} from '@angular/forms';
import {RadioButton} from 'primeng/radiobutton';
import {ProgressBar} from 'primeng/progressbar';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {toSignal} from '@angular/core/rxjs-interop';
import {EnrichmentService} from '../../service/enrichment.service';
import {SmartEnrichmentService} from '../../service/smart-enrichment.service';
import {
  EnrichmentRequest,
  EnrichmentScope,
  EnrichmentStepType,
  EnrichmentWritePolicy,
  SELECTABLE_ENRICHMENT_STEPS,
} from '../../model/enrichment.model';

/**
 * Sets up one enrichment run: which sources may be used, how much of the result may be written, and
 * whether the agent is allowed to run at all.
 *
 * The dialog only queues the work. Progress arrives over the websocket and is reported wherever the
 * user happens to be, because a library-wide run outlives any dialog they would sit in front of.
 */
@Component({
  selector: 'app-enrichment',
  standalone: true,
  imports: [Button, Checkbox, FormsModule, RadioButton, ProgressBar, TranslocoDirective],
  templateUrl: './enrichment.component.html',
  styleUrl: './enrichment.component.scss',
})
export class EnrichmentComponent {
  private readonly config = inject(DynamicDialogConfig);
  private readonly dialogRef = inject(DynamicDialogRef);
  private readonly enrichmentService = inject(EnrichmentService);
  private readonly smartEnrichmentService = inject(SmartEnrichmentService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  readonly scope: EnrichmentScope = this.config.data.scope ?? 'BOOK';
  readonly bookIds: number[] = this.config.data.bookIds ?? [];
  readonly libraryId: number | undefined = this.config.data.libraryId;
  /** Only used to warn about the size of a sweep; the backend resolves the real set. */
  readonly libraryBookCount: number = this.config.data.libraryBookCount ?? 0;

  readonly selectableSteps = SELECTABLE_ENRICHMENT_STEPS;
  readonly selectedSteps = signal<EnrichmentStepType[]>([...SELECTABLE_ENRICHMENT_STEPS]);
  readonly writePolicy = signal<EnrichmentWritePolicy>('AUTO_IF_EMPTY');
  readonly agentAllowed = signal(false);
  readonly submitting = signal(false);

  /**
   * The agent binary is operator-supplied, so most instances do not have it. Hiding the toggle is
   * better than offering one that can only fail.
   */
  readonly agentAvailable = toSignal(this.smartEnrichmentService.available$, {initialValue: false});

  readonly bookCount = computed(() => (this.scope === 'LIBRARY' ? this.libraryBookCount : this.bookIds.length));

  /**
   * A library sweep with the agent on would take one agent call per unresolved book, and those are
   * minutes each. The warning is deliberately about time rather than a hard block: an operator who
   * wants it and is willing to wait days is entitled to ask for it.
   */
  readonly agentWarning = computed(() => this.agentAllowed() && this.bookCount() > 50);

  readonly canSubmit = computed(() => this.selectedSteps().length > 0 && !this.submitting());

  toggleStep(step: EnrichmentStepType, checked: boolean): void {
    this.selectedSteps.update((steps) =>
      checked ? [...new Set([...steps, step])] : steps.filter((selected) => selected !== step)
    );
  }

  isStepSelected(step: EnrichmentStepType): boolean {
    return this.selectedSteps().includes(step);
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.enrichmentService.enrich(this.buildRequest()).subscribe({
      next: (job) => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('metadata.enrichment.queued.summary'),
          detail: this.t.translate('metadata.enrichment.queued.detail', {count: this.bookCount()}),
        });
        this.dialogRef.close({jobId: job.jobId});
      },
      error: () => {
        this.submitting.set(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('metadata.enrichment.failed.summary'),
          detail: this.t.translate('metadata.enrichment.failed.detail'),
        });
      },
    });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  private buildRequest(): EnrichmentRequest {
    return {
      scope: this.scope,
      libraryId: this.libraryId,
      bookIds: this.scope === 'LIBRARY' ? undefined : this.bookIds,
      steps: this.selectedSteps(),
      writePolicy: this.writePolicy(),
      // The backend refuses the agent without this flag even when the step is named, so it is the
      // single place the expensive path is turned on.
      agentAllowed: this.agentAllowed() && this.agentAvailable(),
    };
  }
}
