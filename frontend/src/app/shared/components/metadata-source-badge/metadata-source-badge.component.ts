import {ChangeDetectionStrategy, Component, computed, input, Signal} from '@angular/core';
import {translateSignal} from '@jsverse/transloco';

import {MetadataFieldSources, metadataSourceFor} from '../../metadata/metadata-field-source';
import {METADATA_PROVIDER_LABEL_KEYS, metadataProviderTagColor} from '../../metadata/metadata-provider-display';
import {AppTagComponent} from '../../ui/tag/app-tag.component';
import {AppTooltipDirective} from '../../ui/tooltip/app-tooltip.directive';

/**
 * Says where the value in one metadata field came from, and says nothing at all when nobody knows.
 *
 * The empty case is the common one — a book filled by hand, or filled before the app recorded
 * provenance, has no rows — so this renders no element rather than a placeholder. Twenty greyed-out
 * "unknown" chips down a metadata form would be worse than no feature.
 */
@Component({
  selector: 'app-metadata-source-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppTagComponent, AppTooltipDirective],
  template: `
    @if (provider()) {
      <span class="metadata-source-badge" role="note" [attr.aria-label]="tooltip()" [appTooltip]="tooltip()">
        <app-tag size="sm" [color]="color()" [label]="label()" />
      </span>
    }
  `,
})
export class MetadataSourceBadgeComponent {
  /** The metadata editor's form control name, e.g. `title`. */
  readonly field = input.required<string>();
  readonly sources = input<MetadataFieldSources | null | undefined>();

  protected readonly provider = computed(() => metadataSourceFor(this.sources(), this.field()));
  protected readonly color = computed(() => metadataProviderTagColor(this.provider()));

  private readonly providerLabels: Readonly<Record<string, Signal<string>>> = Object.fromEntries(
    Object.entries(METADATA_PROVIDER_LABEL_KEYS).map(([provider, key]) => [provider, translateSignal(key)]),
  );

  protected readonly label = computed(() => {
    const provider = this.provider();
    if (!provider) {
      return '';
    }
    return this.providerLabels[provider.toLowerCase()]?.() ?? provider;
  });

  private readonly tooltipParams = computed(() => ({provider: this.label()}));
  protected readonly tooltip = translateSignal('metadata.fieldSource.tooltip', this.tooltipParams);
}
