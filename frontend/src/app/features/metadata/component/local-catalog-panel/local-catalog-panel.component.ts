import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {DatePipe} from '@angular/common';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {TranslocoDirective} from '@jsverse/transloco';
import {lastValueFrom} from 'rxjs';

import {AppButtonComponent} from '../../../../shared/ui/button/app-button.component';
import {AppSpinnerComponent} from '../../../../shared/ui/spinner/app-spinner.component';
import {AppTagComponent} from '../../../../shared/ui/tag/app-tag.component';
import {BookDialogHelperService} from '../../../book/components/book-browser/book-dialog-helper.service';
import {EnrichmentService} from '../../service/enrichment.service';
import {LOCAL_CATALOG_ENRICHMENT_STEPS} from '../../model/enrichment.model';
import {LocalCatalogBookView} from '../../model/local-catalog.model';

/**
 * Shows what the library's local catalog holds for one book, next to what the book actually carries.
 * <p>
 * It exists because the two differ and the difference is invisible everywhere else: a write policy
 * of "only into empty fields" leaves the catalog's annotation unused once a provider has filled the
 * description, a locked field is never overwritten, and a book may simply never have been enriched.
 * The panel reads and never writes — applying is the enrichment dialog's job, which already owns the
 * write policy.
 */
@Component({
  selector: 'app-local-catalog-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TranslocoDirective, AppButtonComponent, AppSpinnerComponent, AppTagComponent],
  templateUrl: './local-catalog-panel.component.html',
  styleUrl: './local-catalog-panel.component.scss',
})
export class LocalCatalogPanelComponent {
  readonly bookId = input.required<number>();

  private readonly enrichmentService = inject(EnrichmentService);
  private readonly bookDialogHelperService = inject(BookDialogHelperService);

  /**
   * The catalog is a set of files on disk that only ever changes when an operator replaces them, so
   * a long stale time is honest rather than merely convenient — and it keeps flipping between tabs
   * from re-opening the index.
   */
  private readonly query = injectQuery(() => ({
    queryKey: ['local-catalog-book', this.bookId()] as const,
    queryFn: () => lastValueFrom(this.enrichmentService.localCatalogForBook(this.bookId())),
    staleTime: 10 * 60_000,
    refetchOnWindowFocus: false,
  }));

  readonly loading = computed(() => this.query.isPending());
  readonly failed = computed(() => this.query.isError());
  readonly view = computed<LocalCatalogBookView | null>(() => this.query.data() ?? null);

  readonly catalogKey = computed(() => {
    const view = this.view();
    return view?.sourceArchive && view.sourceArchiveEntry
      ? `${view.sourceArchive}#${view.sourceArchiveEntry}`
      : null;
  });

  /**
   * A book whose library has a catalog that simply holds no row for it. Told apart from an
   * unavailable catalog because they are different answers: nothing to show here versus nothing to
   * show anywhere.
   */
  readonly empty = computed(() => {
    const view = this.view();
    if (!view?.available) {
      return false;
    }
    return !view.title
      && !view.description
      && !view.language
      && view.authors.length === 0
      && view.reviewCount === 0
      && view.authorBios.length === 0
      && view.containingCompilations.length === 0
      && view.compilationParts.length === 0;
  });

  /**
   * Whether the value the catalog holds for a field is also the value the book carries, according to
   * the recorded provenance. Absence is a real answer — "not from here" — never "unknown".
   */
  applied(field: string): boolean {
    return this.view()?.fieldsFromCatalog.includes(field) ?? false;
  }

  /**
   * Opens the enrichment dialog already narrowed to the catalog steps and set to replace, because
   * that is the run this panel is evidence for. It is a preset rather than a silent apply: the
   * dialog still shows every source and every policy, and replacing is a decision — a field the
   * catalog disagrees with may be one somebody corrected by hand.
   */
  async applyFromCatalog(): Promise<void> {
    await this.bookDialogHelperService.openEnrichmentDialog(new Set([this.bookId()]), {
      steps: LOCAL_CATALOG_ENRICHMENT_STEPS,
      writePolicy: 'AUTO',
    });
  }
}
