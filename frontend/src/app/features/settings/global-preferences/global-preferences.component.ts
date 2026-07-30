import {Component, computed, DestroyRef, effect, inject, OnInit, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {MenuItem, MessageService} from 'primeng/api';
import {Button} from 'primeng/button';
import {SplitButton} from 'primeng/splitbutton';
import {ToggleSwitch} from 'primeng/toggleswitch';

import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {BookMetadataManageService} from '../../book/service/book-metadata-manage.service';
import {
  AppSettingKey,
  CoverCroppingSettings,
  RecommendationEmbeddingSettings,
  SmartEnrichmentSettings,
  SmartEnrichmentStatus
} from '../../../shared/model/app-settings.model';
import {InputText} from 'primeng/inputtext';
import {Slider} from 'primeng/slider';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';

@Component({
  selector: 'app-global-preferences',
  standalone: true,
  imports: [
    Button,
    ToggleSwitch,
    FormsModule,
    InputText,
    Slider,
    SplitButton,
    TranslocoDirective,
    TranslocoPipe
  ],
  templateUrl: './global-preferences.component.html',
  styleUrl: './global-preferences.component.scss'
})
export class GlobalPreferencesComponent implements OnInit {

  toggles = {
    autoBookSearch: false,
    similarBookRecommendation: false,
  };

  coverCroppingSettings: CoverCroppingSettings = {
    verticalCroppingEnabled: false,
    horizontalCroppingEnabled: false,
    aspectRatioThreshold: 2.5,
    smartCroppingEnabled: false
  };

  recommendationEmbeddingSettings: RecommendationEmbeddingSettings = {
    ollamaBaseUrl: '',
    model: '',
    dimensions: 512,
    batchSize: 64,
    minSearchSimilarity: 0.42
  };
  availableEmbeddingModels = signal<string[]>([]);
  loadingEmbeddingModels = signal(false);

  // Model and effort are two axes the agent CLI keeps separate: `agy models` lists them fused
  // ("gemini-3.6-flash-high"), but the CLI wants `--model gemini-3.6-flash --effort high`, and some
  // models (gemini-3.1-pro) offer only a subset of efforts while others (claude-*) take none. So the
  // fused list is split into a base-model dropdown and a per-model effort dropdown. Signals, because
  // the effort options must react to the chosen model.
  smartEnrichmentEnabled = signal(false);
  smartEnrichmentModel = signal('');
  smartEnrichmentEffort = signal('');
  smartEnrichmentDeepSearch = signal(false);
  smartEnrichmentStatus = signal<SmartEnrichmentStatus | null>(null);
  loadingSmartEnrichmentStatus = signal(false);
  // A model saved earlier but no longer listed — e.g. after a CLI update dropped it — is kept
  // selectable so the operator is not silently switched away from it without noticing.
  private readonly savedModel = signal('');
  private static readonly EFFORTS = ['low', 'medium', 'high'];

  private readonly agentModelCatalog = computed(() => {
    const raw = this.smartEnrichmentStatus()?.models ?? [];
    const bases: string[] = [];
    const efforts = new Map<string, string[]>();
    for (const id of raw) {
      const dash = id.lastIndexOf('-');
      const suffix = dash >= 0 ? id.slice(dash + 1) : '';
      const hasEffort = GlobalPreferencesComponent.EFFORTS.includes(suffix);
      const base = hasEffort ? id.slice(0, dash) : id;
      if (!efforts.has(base)) {
        efforts.set(base, []);
        bases.push(base);
      }
      if (hasEffort) {
        efforts.get(base)!.push(suffix);
      }
    }
    // Present efforts in strength order regardless of how the CLI listed them.
    for (const [base, list] of efforts) {
      efforts.set(base, GlobalPreferencesComponent.EFFORTS.filter((e) => list.includes(e)));
    }
    const current = this.savedModel();
    if (current && !bases.includes(current)) {
      bases.unshift(current);
      efforts.set(current, efforts.get(current) ?? []);
    }
    return {bases, efforts};
  });
  readonly agentBaseModels = computed(() => this.agentModelCatalog().bases);
  readonly agentEffortOptions = computed(() => this.agentModelCatalog().efforts.get(this.smartEnrichmentModel()) ?? []);

  testingSmartEnrichment = signal(false);
  smartEnrichmentTestMessage = signal<string | null>(null);
  smartEnrichmentTestPassed = signal(false);

  private readonly appSettingsService = inject(AppSettingsService);
  private readonly bookMetadataManageService = inject(BookMetadataManageService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly syncSettingsEffect = effect(() => {
    const settings = this.appSettingsService.appSettings();
    if (!settings) {
      return;
    }

    if (settings.maxFileUploadSizeInMb) {
      this.maxFileUploadSizeInMb = settings.maxFileUploadSizeInMb;
    }
    if (settings.coverCroppingSettings) {
      this.coverCroppingSettings = {...settings.coverCroppingSettings};
    }
    this.toggles.autoBookSearch = settings.autoBookSearch ?? false;
    this.toggles.similarBookRecommendation = settings.similarBookRecommendation ?? false;
    if (settings.recommendationEmbeddingSettings) {
      this.recommendationEmbeddingSettings = {...settings.recommendationEmbeddingSettings};
    }
    if (settings.smartEnrichmentSettings) {
      const stored = settings.smartEnrichmentSettings;
      const {base, effort} = this.splitStoredModel(stored.model ?? '', stored.effort ?? '');
      this.smartEnrichmentEnabled.set(stored.enabled);
      this.smartEnrichmentModel.set(base);
      this.smartEnrichmentEffort.set(effort);
      this.smartEnrichmentDeepSearch.set(stored.deepSearch ?? false);
      this.savedModel.set(base);
    }
  });

  maxFileUploadSizeInMb?: number;
  regenerateCoverMenuItems: MenuItem[] = [];

  ngOnInit(): void {
    this.regenerateCoverMenuItems = [
      {
        label: this.t.translate('settingsApp.covers.regenerateMissingBtn'),
        icon: 'pi pi-images',
        command: () => this.regenerateCovers(true)
      }
    ];
    this.refreshEmbeddingModels();
    this.refreshSmartEnrichmentStatus();
  }

  refreshSmartEnrichmentStatus(): void {
    this.loadingSmartEnrichmentStatus.set(true);
    this.appSettingsService.getSmartEnrichmentStatus().pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: status => {
        this.smartEnrichmentStatus.set(status);
        this.loadingSmartEnrichmentStatus.set(false);
      },
      // A backend without the endpoint, or one that cannot probe the binary, reads as "not
      // installed" — the same state the operator would see if it really were missing.
      error: () => {
        this.smartEnrichmentStatus.set({installed: false, version: null, authenticated: false, models: []});
        this.loadingSmartEnrichmentStatus.set(false);
      }
    });
  }

  /**
   * Legacy rows stored the fused id in `model` with a blank `effort`. Split it on read so the two
   * dropdowns show the right thing; a row that already used the split form is left as-is.
   */
  private splitStoredModel(model: string, effort: string): {base: string; effort: string} {
    const trimmedModel = model.trim();
    const trimmedEffort = effort.trim();
    if (trimmedEffort && GlobalPreferencesComponent.EFFORTS.includes(trimmedEffort)) {
      return {base: trimmedModel, effort: trimmedEffort};
    }
    const dash = trimmedModel.lastIndexOf('-');
    const suffix = dash >= 0 ? trimmedModel.slice(dash + 1) : '';
    if (GlobalPreferencesComponent.EFFORTS.includes(suffix)) {
      return {base: trimmedModel.slice(0, dash), effort: suffix};
    }
    return {base: trimmedModel, effort: ''};
  }

  onAgentModelChange(model: string): void {
    this.smartEnrichmentModel.set(model);
    const efforts = this.agentModelCatalog().efforts.get(model) ?? [];
    if (efforts.length === 0) {
      // A model with no effort variants (e.g. claude-*) takes no --effort at all.
      this.smartEnrichmentEffort.set('');
    } else if (!efforts.includes(this.smartEnrichmentEffort())) {
      // Effort is mandatory once a model offers it, so land on a sensible one rather than blank.
      this.smartEnrichmentEffort.set(efforts.includes('medium') ? 'medium' : efforts[efforts.length - 1]);
    }
  }

  effortLabel(effort: string): string {
    const key = `settingsApp.smartEnrichment.effort${effort.charAt(0).toUpperCase()}${effort.slice(1)}`;
    return this.t.translate(key);
  }

  saveSmartEnrichmentSettings(): void {
    const settings: SmartEnrichmentSettings = {
      enabled: this.smartEnrichmentEnabled(),
      model: this.smartEnrichmentModel().trim(),
      effort: this.smartEnrichmentEffort().trim(),
      deepSearch: this.smartEnrichmentDeepSearch()
    };
    this.savedModel.set(settings.model);
    this.saveSetting(AppSettingKey.SMART_ENRICHMENT_SETTINGS, settings);
  }

  testSmartEnrichment(): void {
    this.testingSmartEnrichment.set(true);
    this.smartEnrichmentTestMessage.set(null);
    this.appSettingsService.testSmartEnrichment().pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: result => {
        this.testingSmartEnrichment.set(false);
        this.smartEnrichmentTestPassed.set(result.success);
        this.smartEnrichmentTestMessage.set(result.message);
        // A successful run proves the credentials the status endpoint could only guess at from a
        // file on disk, so the badge is refreshed alongside it.
        this.refreshSmartEnrichmentStatus();
      },
      error: error => {
        this.testingSmartEnrichment.set(false);
        this.smartEnrichmentTestPassed.set(false);
        this.smartEnrichmentTestMessage.set(
          error?.error?.message ?? this.t.translate('settingsApp.smartEnrichment.testError')
        );
      }
    });
  }

  refreshEmbeddingModels(): void {
    this.loadingEmbeddingModels.set(true);
    this.appSettingsService.getRecommendationEmbeddingModels().pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: models => {
        this.availableEmbeddingModels.set(models);
        this.loadingEmbeddingModels.set(false);
      },
      error: () => {
        this.loadingEmbeddingModels.set(false);
        this.showMessage(
          'error',
          this.t.translate('common.error'),
          this.t.translate('settingsApp.recommendations.modelsError')
        );
      }
    });
  }

  saveRecommendationEmbeddingSettings(): void {
    const settings = {
      ...this.recommendationEmbeddingSettings,
      ollamaBaseUrl: this.recommendationEmbeddingSettings.ollamaBaseUrl.trim(),
      model: this.recommendationEmbeddingSettings.model.trim()
    };
    if (!settings.ollamaBaseUrl || !settings.model || settings.batchSize < 1 || settings.batchSize > 256) {
      this.showMessage(
        'error',
        this.t.translate('settingsApp.recommendations.invalid'),
        this.t.translate('settingsApp.recommendations.invalidDetail')
      );
      return;
    }
    this.recommendationEmbeddingSettings = settings;
    this.saveSetting(AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS, settings);
  }

  onToggleChange(settingKey: keyof typeof this.toggles, checked: boolean): void {
    this.toggles[settingKey] = checked;
    const toggleKeyMap: Record<string, AppSettingKey> = {
      autoBookSearch: AppSettingKey.AUTO_BOOK_SEARCH,
      similarBookRecommendation: AppSettingKey.SIMILAR_BOOK_RECOMMENDATION,
    };
    const keyToSend = toggleKeyMap[settingKey];
    if (keyToSend) {
      this.saveSetting(keyToSend, checked);
    } else {
      console.warn(`Unknown toggle key: ${settingKey}`);
    }
  }

  onCoverCroppingChange(): void {
    this.saveSetting(AppSettingKey.COVER_CROPPING_SETTINGS, this.coverCroppingSettings);
  }

  saveFileSize() {
    if (!this.maxFileUploadSizeInMb || this.maxFileUploadSizeInMb <= 0) {
      this.showMessage('error', this.t.translate('settingsApp.fileManagement.invalidInput'), this.t.translate('settingsApp.fileManagement.invalidInputDetail'));
      return;
    }
    this.saveSetting(AppSettingKey.MAX_FILE_UPLOAD_SIZE_IN_MB, this.maxFileUploadSizeInMb);
  }

  regenerateCovers(missingOnly = false): void {
    this.bookMetadataManageService.regenerateCovers(missingOnly).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: () =>
        this.showMessage('success', this.t.translate('settingsApp.covers.regenerateStarted'), this.t.translate('settingsApp.covers.regenerateStartedDetail')),
      error: () =>
        this.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsApp.covers.regenerateError'))
    });
  }

  private saveSetting(key: string, value: unknown): void {
    this.appSettingsService.saveSettings([{key, newValue: value}]).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: () =>
        this.showMessage('success', this.t.translate('settingsApp.settingsSaved'), this.t.translate('settingsApp.settingsSavedDetail')),
      error: () =>
        this.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsApp.settingsError'))
    });
  }

  private showMessage(severity: 'success' | 'error', summary: string, detail: string): void {
    this.messageService.add({severity, summary, detail});
  }
}
