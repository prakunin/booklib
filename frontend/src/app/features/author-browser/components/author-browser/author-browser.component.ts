import {Component, computed, DestroyRef, effect, ElementRef, HostListener, inject, OnInit, signal, viewChild} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {ProgressSpinner} from 'primeng/progressspinner';
import {InputText} from 'primeng/inputtext';
import {Select} from 'primeng/select';
import {Popover} from 'primeng/popover';
import {Button} from 'primeng/button';
import {Divider} from 'primeng/divider';
import {Tooltip} from 'primeng/tooltip';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {RouteScrollPositionService} from '../../../../shared/service/route-scroll-position.service';
import {MessageService} from 'primeng/api';
import {AuthorBrowserSort, AuthorService} from '../../service/author.service';
import {AuthorSummary, AuthorFilters, DEFAULT_AUTHOR_FILTERS} from '../../model/author.model';
import {AuthorCardComponent} from '../author-card/author-card.component';
import {AuthorSelectionService, AuthorCheckboxClickEvent} from '../../service/author-selection.service';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {ActivatedRoute, Router} from '@angular/router';
import {UserService} from '../../../settings/user-management/user.service';
import {AppMessageComponent} from '../../../../shared/ui/message/app-message.component';
import {createVirtualGrid} from '../../../../shared/util/virtual-grid.util';
import {GridDensityButtonsComponent, type GridDensityDirection} from '../../../../shared/components/grid-density-buttons/grid-density-buttons.component';
import {LocalStorageService} from '../../../../shared/service/local-storage.service';
import {ScalePreference} from '../../../../shared/util/scale-preference.util';
import {LayoutService} from '../../../../shared/layout/layout.service';
import {createGridDensity} from '../../../../shared/util/grid-density.util';
import {LibraryService} from '../../../book/service/library.service';

type SortDirection = 'asc' | 'desc';

interface SortOption {
  label: string;
  value: string;
}

interface FilterOption {
  label: string;
  value: string | number;
}

const DEFAULT_SORT_DIRECTIONS: Record<string, SortDirection> = {
  'name': 'asc',
  'book-count': 'desc',
  'matched': 'desc',
  'recently-added': 'desc',
  'recently-read': 'desc',
  'reading-progress': 'desc',
  'avg-rating': 'desc',
  'photo': 'desc',
  'series-count': 'desc'
};

@Component({
  selector: 'app-author-browser',
  standalone: true,
  templateUrl: './author-browser.component.html',
  styleUrls: ['./author-browser.component.scss'],
  imports: [
    FormsModule,
    ProgressSpinner,
    AppMessageComponent,
    InputText,
    Select,
    Popover,
    Button,
    Divider,
    Tooltip,
    TranslocoDirective,
    TranslocoPipe,
    GridDensityButtonsComponent,
    AuthorCardComponent,
  ]
})
export class AuthorBrowserComponent implements OnInit {

  private static readonly BASE_WIDTH = 165;
  private static readonly BASE_HEIGHT = 290;
  private static readonly MOBILE_BASE_WIDTH = 140;
  private static readonly MOBILE_BASE_HEIGHT = 250;
  private static readonly DEFAULT_MOBILE_GRID_COLUMNS = 3;
  private static readonly MIN_MOBILE_GRID_COLUMNS = 2;
  private static readonly MAX_MOBILE_GRID_COLUMNS = 4;
  private static readonly SCALE_STORAGE_KEY = 'authorScalePreference';
  private static readonly MOBILE_COLUMNS_STORAGE_KEY = 'authorMobileColumnsPreference';
  private static readonly MIN_SCALE = 0.7;
  private static readonly MAX_SCALE = 1.3;
  private static readonly SEARCH_DEBOUNCE_MS = 500;

  private readonly authorService = inject(AuthorService);
  private readonly libraryService = inject(LibraryService);
  private readonly messageService = inject(MessageService);
  private readonly pageTitle = inject(PageTitleService);
  private readonly scrollService = inject(RouteScrollPositionService);
  private readonly t = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly activatedRoute = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly localStorageService = inject(LocalStorageService);
  private readonly layoutService = inject(LayoutService);
  protected userService = inject(UserService);
  protected selectionService = inject(AuthorSelectionService);

  constructor() {
    effect(() => {
      this.selectionService.setCurrentAuthors(this.filteredAuthors());
    });

    effect(() => {
      this.fetchNextPageIfNearLoadedEnd();
    });
  }

  private static readonly GRID_GAP = 20;
  private readonly scrollElement = viewChild<ElementRef<HTMLElement>>('scrollElement');
  private readonly initialScrollOffset = () => this.scrollService.getPosition(this.scrollService.keyFor(this.activatedRoute)) ?? 0;
  private readonly scalePreference = new ScalePreference(this.localStorageService, {
    storageKey: AuthorBrowserComponent.SCALE_STORAGE_KEY,
    minScale: AuthorBrowserComponent.MIN_SCALE,
    maxScale: AuthorBrowserComponent.MAX_SCALE,
  });
  private readonly scaleFactor = this.scalePreference.scaleFactor;

  readonly screenWidth = signal(globalThis.window !== undefined ? globalThis.window.innerWidth : 1024);
  thumbnailCacheBusters = new Map<number, number>();
  private readonly selectedAuthors = this.selectionService.selectedAuthors;
  private searchDebounceTimer: ReturnType<typeof setTimeout> | null = null;

  readonly loading = this.authorService.isAuthorsLoading;
  readonly loadError = this.authorService.isAuthorsError;
  readonly isFetchingNextPage = this.authorService.isFetchingNextPage;
  protected currentUser = this.userService.currentUser;
  selectedCount = computed(() => this.selectedAuthors().size);
  searchTerm = signal('');
  sortBy = signal('name');
  sortDirection = signal<SortDirection>('asc');
  filters = signal<AuthorFilters>({...DEFAULT_AUTHOR_FILTERS});
  libraryOptions = computed<FilterOption[]>(() => {
    const allLabel = this.t.translate('authorBrowser.filters.all');
    return [
      {label: allLabel, value: 'all'},
      ...this.libraryService.libraries()
        .filter((library): library is typeof library & {id: number} => library.id !== undefined)
        .map(library => ({label: library.name, value: library.id}))
    ];
  });
  genreOptions = computed<FilterOption[]>(() => {
    const allLabel = this.t.translate('authorBrowser.filters.all');
    return [
      {label: allLabel, value: 'all'},
      ...this.authorService.authorCategories().map(category => ({label: category, value: category}))
    ];
  });
  activeFilterCount = computed(() => {
    const filters = this.filters();
    let count = 0;
    if (filters.matchStatus !== 'all') count++;
    if (filters.photoStatus !== 'all') count++;
    if (filters.readStatus !== 'all') count++;
    if (filters.bookCount !== 'all') count++;
    if (filters.library !== 'all') count++;
    if (filters.genre !== 'all') count++;
    return count;
  });
  readonly filteredAuthors = this.authorService.allAuthors;
  @HostListener('window:resize')
  onResize(): void {
    this.screenWidth.set(window.innerWidth);
  }

  readonly isMobile = computed(() => !this.layoutService.isDesktop());
  private readonly baseCardWidth = computed(() => this.isMobile()
    ? AuthorBrowserComponent.MOBILE_BASE_WIDTH
    : AuthorBrowserComponent.BASE_WIDTH
  );
  private readonly gridDensity = createGridDensity(this.localStorageService, {
    useFixedColumns: this.isMobile,
    screenWidth: this.screenWidth,
    storageKey: AuthorBrowserComponent.MOBILE_COLUMNS_STORAGE_KEY,
    defaultColumns: AuthorBrowserComponent.DEFAULT_MOBILE_GRID_COLUMNS,
    minColumns: AuthorBrowserComponent.MIN_MOBILE_GRID_COLUMNS,
    maxColumns: AuthorBrowserComponent.MAX_MOBILE_GRID_COLUMNS,
    scale: this.scaleFactor,
    minScale: AuthorBrowserComponent.MIN_SCALE,
    maxScale: AuthorBrowserComponent.MAX_SCALE,
    gap: AuthorBrowserComponent.GRID_GAP,
    baseWidth: this.baseCardWidth,
    setScale: scale => this.scalePreference.setScale(scale),
  });
  readonly gridDensitySmallerDisabled = this.gridDensity.smallerDisabled;
  readonly gridDensityLargerDisabled = this.gridDensity.largerDisabled;
  private readonly cardAspectRatio = computed(() => {
    const baseHeight = this.isMobile()
      ? AuthorBrowserComponent.MOBILE_BASE_HEIGHT
      : AuthorBrowserComponent.BASE_HEIGHT;
    return baseHeight / this.baseCardWidth();
  });
  private readonly minCardWidth = computed(() => this.isMobile()
    ? 1
    : Math.round(this.baseCardWidth() * this.scaleFactor())
  );
  readonly virtualGrid = createVirtualGrid({
    items: this.filteredAuthors,
    count: this.authorService.totalAuthors,
    scrollElement: this.scrollElement,
    minItemWidth: this.minCardWidth,
    gap: this.gridDensity.gap,
    columns: this.gridDensity.columns,
    initialOffset: this.initialScrollOffset,
    fillItemWidth: true,
    estimateItemHeight: itemWidth => Math.round(itemWidth * this.cardAspectRatio()),
  });

  sortOptions: SortOption[] = [];

  private readonly validSortValues = [
    'name', 'book-count', 'matched', 'recently-added', 'recently-read',
    'reading-progress', 'avg-rating', 'photo', 'series-count'
  ];

  ngOnInit(): void {
    this.pageTitle.setPageTitle(this.t.translate('authorBrowser.pageTitle'));
    this.authorService.enableBrowser();

    this.sortOptions = [
      {label: this.t.translate('authorBrowser.sort.name'), value: 'name'},
      {label: this.t.translate('authorBrowser.sort.bookCount'), value: 'book-count'},
      {label: this.t.translate('authorBrowser.sort.matched'), value: 'matched'},
      {label: this.t.translate('authorBrowser.sort.recentlyAdded'), value: 'recently-added'},
      {label: this.t.translate('authorBrowser.sort.recentlyRead'), value: 'recently-read'},
      {label: this.t.translate('authorBrowser.sort.readingProgress'), value: 'reading-progress'},
      {label: this.t.translate('authorBrowser.sort.avgRating'), value: 'avg-rating'},
      {label: this.t.translate('authorBrowser.sort.photo'), value: 'photo'},
      {label: this.t.translate('authorBrowser.sort.seriesCount'), value: 'series-count'}
    ];

    const sortParam = this.activatedRoute.snapshot.queryParamMap.get('sort');
    const dirParam = this.activatedRoute.snapshot.queryParamMap.get('dir') as SortDirection | null;
    if (sortParam && this.validSortValues.includes(sortParam)) {
      this.sortBy.set(sortParam);
      this.sortDirection.set(dirParam === 'asc' || dirParam === 'desc' ? dirParam : DEFAULT_SORT_DIRECTIONS[sortParam]);
    }
    this.authorService.setBrowserSort(this.sortBy() as AuthorBrowserSort, this.sortDirection());

    this.scrollService.trackRoute({
      scrollElement: this.scrollElement,
      route: this.activatedRoute,
      destroyRef: this.destroyRef,
      dismissOverlaysBeforeSave: true,
    });
    this.destroyRef.onDestroy(() => {
      this.selectionService.deselectAll();
      if (this.searchDebounceTimer !== null) {
        clearTimeout(this.searchDebounceTimer);
      }
    });
  }

  adjustGridDensity(direction: GridDensityDirection): void {
    this.gridDensity.adjust(direction, this.virtualGrid);
  }

  onSearchChange(value: string): void {
    this.searchTerm.set(value);
    if (this.searchDebounceTimer !== null) {
      clearTimeout(this.searchDebounceTimer);
    }
    this.searchDebounceTimer = setTimeout(() => {
      this.searchDebounceTimer = null;
      this.authorService.setBrowserSearch(value);
      this.scrollToTop();
    }, AuthorBrowserComponent.SEARCH_DEBOUNCE_MS);
  }

  onSortChange(value: string): void {
    this.sortBy.set(value);
    const nextDirection = DEFAULT_SORT_DIRECTIONS[value] || 'asc';
    this.sortDirection.set(nextDirection);
    this.authorService.setBrowserSort(value as AuthorBrowserSort, nextDirection);
    this.updateSortQueryParams(value, nextDirection);
    this.scrollToTop();
  }

  toggleSortDirection(): void {
    const next: SortDirection = this.sortDirection() === 'asc' ? 'desc' : 'asc';
    this.sortDirection.set(next);
    this.authorService.setBrowserSort(this.sortBy() as AuthorBrowserSort, next);
    this.updateSortQueryParams(this.sortBy(), next);
    this.scrollToTop();
  }

  onFilterChange(key: keyof AuthorFilters, value: string | number): void {
    this.filters.update(current => ({...current, [key]: value} as AuthorFilters));
    this.authorService.setBrowserFilters(this.filters());
    this.scrollToTop();
  }

  resetFilters(): void {
    this.filters.set({...DEFAULT_AUTHOR_FILTERS});
    this.authorService.setBrowserFilters(this.filters());
    this.scrollToTop();
  }

  get canEditMetadata(): boolean {
    const user = this.userService.currentUser();
    return !!user?.permissions?.admin || !!user?.permissions?.canEditMetadata;
  }

  get canDeleteBook(): boolean {
    const user = this.userService.currentUser();
    return !!user?.permissions?.admin || !!user?.permissions?.canDeleteBook;
  }

  isAuthorSelected(authorId: number): boolean {
    return this.selectedAuthors().has(authorId);
  }

  onCheckboxClicked(event: AuthorCheckboxClickEvent): void {
    this.selectionService.handleCheckboxClick(event);
  }

  selectAllAuthors(): void {
    this.selectionService.selectAll();
  }

  deselectAllAuthors(): void {
    this.selectionService.deselectAll();
  }

  navigateToAuthor(author: AuthorSummary): void {
    this.router.navigate(['/author', author.id]);
  }

  navigateToAuthorEdit(author: AuthorSummary): void {
    this.router.navigate(['/author', author.id], {queryParams: {tab: 'edit'}});
  }

  deleteAuthor(author: AuthorSummary): void {
    this.authorService.deleteAuthors([author.id]).subscribe({
      next: () => {
        this.removeAuthorsFromList([author.id]);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('authorBrowser.toast.deleteSuccessSummary'),
          detail: this.t.translate('authorBrowser.toast.deleteSuccessDetail')
        });
      },
      error: () => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('authorBrowser.toast.deleteFailedSummary'),
          detail: this.t.translate('authorBrowser.toast.deleteFailedDetail')
        });
      }
    });
  }

  onAuthorQuickMatched(updated: AuthorSummary): void {
    this.thumbnailCacheBusters.set(updated.id, updated.photoLastModified ?? Date.now());
    this.authorService.patchAuthorInCache(updated.id, updated);
    this.authorService.refreshAuthorPages();
  }

  autoMatchSelected(): void {
    const ids = this.selectionService.getSelectedIds();
    this.selectionService.deselectAll();
    this.authorService.autoMatchAuthors(ids).subscribe({
      next: (matched) => {
        this.thumbnailCacheBusters.set(matched.id, matched.photoLastModified ?? Date.now());
        this.authorService.patchAuthorInCache(matched.id, {
          asin: matched.asin,
          hasPhoto: matched.hasPhoto,
          photoLastModified: matched.photoLastModified,
        });
      },
      complete: () => {
        this.authorService.refreshAuthorPages();
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('authorBrowser.toast.autoMatchSuccessSummary'),
          detail: this.t.translate('authorBrowser.toast.autoMatchSuccessDetail')
        });
      },
      error: () => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('authorBrowser.toast.autoMatchFailedSummary'),
          detail: this.t.translate('authorBrowser.toast.autoMatchFailedDetail')
        });
      }
    });
  }

  deleteSelected(): void {
    const ids = this.selectionService.getSelectedIds();
    this.authorService.deleteAuthors(ids).subscribe({
      next: () => {
        this.selectionService.deselectAll();
        this.removeAuthorsFromList(ids);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('authorBrowser.toast.deleteSelectedSuccessSummary'),
          detail: this.t.translate('authorBrowser.toast.deleteSelectedSuccessDetail')
        });
      },
      error: () => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('authorBrowser.toast.deleteFailedSummary'),
          detail: this.t.translate('authorBrowser.toast.deleteFailedDetail')
        });
      }
    });
  }

  private updateSortQueryParams(sort: string, dir: SortDirection): void {
    this.router.navigate([], {
      queryParams: {sort, dir},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  private removeAuthorsFromList(ids: number[]): void {
    this.authorService.removeAuthorsFromCache(ids);
    this.authorService.invalidateAuthors();
  }

  onAuthorsScroll(): void {
    this.fetchNextPageIfNearLoadedEnd();
  }

  private fetchNextPageIfNearLoadedEnd(): void {
    if (this.isFetchingNextPage()) return;

    const loaded = this.filteredAuthors().length;
    const total = this.authorService.totalAuthors();
    if (loaded >= total) return;

    const lastVisibleIndex = this.virtualGrid.virtualizer.getVirtualItems().at(-1)?.index ?? 0;
    const preloadItems = Math.max(this.virtualGrid.gridColumns() * 4, 12);
    if (lastVisibleIndex >= loaded - preloadItems) {
      this.authorService.fetchNextPage();
    }
  }

  private scrollToTop(): void {
    queueMicrotask(() => this.virtualGrid.virtualizer.scrollToOffset(0));
  }
}
