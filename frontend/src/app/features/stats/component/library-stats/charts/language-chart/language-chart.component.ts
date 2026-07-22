import {Component, computed, inject} from '@angular/core';
import {BaseChartDirective} from 'ng2-charts';
import {ChartConfiguration, ChartData} from 'chart.js';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LibraryStatsApiService} from '../../service/library-stats-api.service';

interface LanguageStats {
  language: string;
  displayName: string;
  count: number;
  percentage: number;
}

type LanguageChartData = ChartData<'pie', number[], string>;

// Professional color palette for languages
const LANGUAGE_COLORS = [
  '#2563EB', // Blue
  '#0D9488', // Teal
  '#7C3AED', // Violet
  '#DC2626', // Red
  '#F59E0B', // Amber
  '#16A34A', // Green
  '#EC4899', // Pink
  '#8B5CF6', // Purple
  '#06B6D4', // Cyan
  '#EA580C', // Orange
  '#6366F1', // Indigo
  '#14B8A6', // Teal-500
  '#F43F5E', // Rose
  '#84CC16', // Lime
  '#A855F7'  // Purple-500
] as const;

// Common language code to display name mapping
const LANGUAGE_NAMES: Record<string, string> = {
  'en': 'English',
  'eng': 'English',
  'english': 'English',
  'es': 'Spanish',
  'spa': 'Spanish',
  'spanish': 'Spanish',
  'fr': 'French',
  'fra': 'French',
  'french': 'French',
  'de': 'German',
  'deu': 'German',
  'german': 'German',
  'it': 'Italian',
  'ita': 'Italian',
  'italian': 'Italian',
  'pt': 'Portuguese',
  'por': 'Portuguese',
  'portuguese': 'Portuguese',
  'ru': 'Russian',
  'rus': 'Russian',
  'russian': 'Russian',
  'zh': 'Chinese',
  'zho': 'Chinese',
  'chinese': 'Chinese',
  'ja': 'Japanese',
  'jpn': 'Japanese',
  'japanese': 'Japanese',
  'ko': 'Korean',
  'kor': 'Korean',
  'korean': 'Korean',
  'pl': 'Polish',
  'pol': 'Polish',
  'polish': 'Polish',
  'nl': 'Dutch',
  'nld': 'Dutch',
  'dutch': 'Dutch',
  'sv': 'Swedish',
  'swe': 'Swedish',
  'swedish': 'Swedish',
  'ar': 'Arabic',
  'ara': 'Arabic',
  'arabic': 'Arabic',
  'hi': 'Hindi',
  'hin': 'Hindi',
  'hindi': 'Hindi',
  'tr': 'Turkish',
  'tur': 'Turkish',
  'turkish': 'Turkish',
  'cs': 'Czech',
  'ces': 'Czech',
  'czech': 'Czech',
  'da': 'Danish',
  'dan': 'Danish',
  'danish': 'Danish',
  'fi': 'Finnish',
  'fin': 'Finnish',
  'finnish': 'Finnish',
  'no': 'Norwegian',
  'nor': 'Norwegian',
  'norwegian': 'Norwegian',
  'uk': 'Ukrainian',
  'ukr': 'Ukrainian',
  'ukrainian': 'Ukrainian',
  'he': 'Hebrew',
  'heb': 'Hebrew',
  'hebrew': 'Hebrew',
  'el': 'Greek',
  'ell': 'Greek',
  'greek': 'Greek',
  'hu': 'Hungarian',
  'hun': 'Hungarian',
  'hungarian': 'Hungarian',
  'ro': 'Romanian',
  'ron': 'Romanian',
  'romanian': 'Romanian',
  'th': 'Thai',
  'tha': 'Thai',
  'thai': 'Thai',
  'vi': 'Vietnamese',
  'vie': 'Vietnamese',
  'vietnamese': 'Vietnamese',
  'id': 'Indonesian',
  'ind': 'Indonesian',
  'indonesian': 'Indonesian',
  'ms': 'Malay',
  'msa': 'Malay',
  'malay': 'Malay'
};

@Component({
  selector: 'app-language-chart',
  standalone: true,
  imports: [BaseChartDirective, TranslocoDirective],
  templateUrl: './language-chart.component.html',
  styleUrls: ['./language-chart.component.scss']
})
export class LanguageChartComponent {
  private readonly libraryStats = inject(LibraryStatsApiService);
  private readonly t = inject(TranslocoService);

  public readonly chartType = 'pie' as const;
  public readonly languageStats = computed<LanguageStats[]>(() => {
    const languages = this.libraryStats.facets()?.languages ?? [];
    const total = languages.reduce((sum, item) => sum + item.count, 0);
    return languages.slice(0, 15).map(item => ({
      language: item.code,
      displayName: item.label || this.getDisplayName(item.code),
      count: item.count,
      percentage: total > 0 ? item.count / total * 100 : 0
    }));
  });
  public readonly totalBooks = computed(() => this.libraryStats.data()?.totalBooks ?? 0);
  public readonly booksWithLanguage = computed(() => this.languageStats().reduce((sum, s) => sum + s.count, 0));

  public readonly chartOptions: ChartConfiguration<'pie'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    layout: {
      padding: {top: 10, bottom: 10}
    },
    plugins: {
      legend: {
        display: true,
        position: 'right',
        labels: {
          font: {
            family: "'Inter', sans-serif",
            size: 12
          },
          usePointStyle: true,
          pointStyle: 'circle',
          padding: 15
        }
      },
      tooltip: {
        enabled: true,
        borderColor: '#2563EB',
        borderWidth: 2,
        cornerRadius: 8,
        padding: 12,
        titleFont: {size: 14, weight: 'bold'},
        bodyFont: {size: 12},
        callbacks: {
          label: (context) => {
            const value = context.parsed;
            const total = context.dataset.data.reduce((a: number, b: number) => a + b, 0);
            const percentage = ((value / total) * 100).toFixed(1);
            return this.t.translate('statsLibrary.language.tooltipLabel', {label: context.label, value, percentage});
          }
        }
      },
      datalabels: {
        display: false
      }
    }
  };

  public readonly chartData = computed<LanguageChartData>(() => {
    const stats = this.languageStats();
    if (stats.length === 0) {
      return {labels: [], datasets: []};
    }

    const labels = stats.map(s => s.displayName);
    const data = stats.map(s => s.count);
    const colors = stats.map((_, index) => LANGUAGE_COLORS[index % LANGUAGE_COLORS.length]);

    return {
      labels,
      datasets: [{
        data,
        backgroundColor: colors
      }]
    };
  });

  private getDisplayName(language: string): string {
    const lower = language.toLowerCase();
    if (LANGUAGE_NAMES[lower]) {
      return LANGUAGE_NAMES[lower];
    }
    // Capitalize first letter if no mapping found
    return language.charAt(0).toUpperCase() + language.slice(1);
  }
}
