import { AfterViewChecked, Component, computed, DestroyRef, ElementRef, inject, OnInit, signal, viewChild } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { NgClass } from '@angular/common';
import { Tab, TabList, TabPanel, TabPanels, Tabs } from 'primeng/tabs';
import { ProgressSpinner } from 'primeng/progressspinner';
import { Button } from 'primeng/button';
import { Tag } from 'primeng/tag';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { MessageService } from 'primeng/api';
import { Tooltip } from 'primeng/tooltip';
import { AuthorService } from '../../service/author.service';
import { AuthorDetails } from '../../model/author.model';
import { Book } from '../../../book/model/book.model';
import { BookCardComponent } from '../../../book/components/book-browser/book-card/book-card.component';
import { CoverScalePreferenceService } from '../../../book/components/book-browser/cover-scale-preference.service';
import { BookCardOverlayPreferenceService } from '../../../book/components/book-browser/book-card-overlay-preference.service';
import { UserService } from '../../../settings/user-management/user.service';
import { AuthorMatchComponent } from '../author-match/author-match.component';
import { AuthorEditorComponent } from '../author-editor/author-editor.component';
import { PageTitleService } from '../../../../shared/service/page-title.service';
import { createVirtualGrid } from '../../../../shared/util/virtual-grid.util';
import { finalize } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-author-detail',
  standalone: true,
  templateUrl: './author-detail.component.html',
  styleUrls: ['./author-detail.component.scss'],
  imports: [
    NgClass,
    Tabs,
    TabList,
    Tab,
    TabPanels,
    TabPanel,
    ProgressSpinner,
    Button,
    Tag,
    TranslocoDirective,
    TranslocoPipe,
    Tooltip,
    BookCardComponent,
    AuthorMatchComponent,
    AuthorEditorComponent
  ]
})
export class AuthorDetailComponent implements OnInit, AfterViewChecked {

  private static readonly GRID_GAP = 21;

  private readonly route = inject(ActivatedRoute);
  private readonly authorService = inject(AuthorService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly messageService = inject(MessageService);
  protected coverScalePreferenceService = inject(CoverScalePreferenceService);
  protected bookCardOverlayPreferenceService = inject(BookCardOverlayPreferenceService);
  protected userService = inject(UserService);
  private readonly pageTitle = inject(PageTitleService);
  private readonly t = inject(TranslocoService);

  readonly descriptionContentRef = viewChild<ElementRef<HTMLElement>>('descriptionContent');
  private readonly scrollElement = viewChild<ElementRef<HTMLElement>>('scrollElement');

  loading = signal(true);
  tab = 'books';
  isExpanded = false;
  isOverflowing = false;
  hasPhoto = true;
  photoTimestamp = 0;
  quickMatching = false;
  private readonly authorState = signal<AuthorDetails | null>(null);
  private readonly authorBooksState = signal<Book[]>([]);
  private hasNextBookPage = false;
  private nextBookPage = 0;
  author = this.authorState.asReadonly();
  authorBooks = this.authorBooksState.asReadonly();
  authorBookCount = signal(0);
  booksLoading = signal(true);
  booksLoadError = signal(false);
  isFetchingNextPage = signal(false);

  get currentCardSize() {
    return this.coverScalePreferenceService.currentCardSize();
  }

  readonly virtualGrid = createVirtualGrid({
    items: this.authorBooks,
    count: this.authorBookCount,
    scrollElement: this.scrollElement,
    minItemWidth: computed(() => this.currentCardSize.width),
    estimateItemHeight: () => this.currentCardSize.height,
    gap: AuthorDetailComponent.GRID_GAP,
  });

  get photoUrl(): string {
    const author = this.author();
    if (!author) return '';
    return this.authorService.getAuthorPhotoUrl(author.id, this.photoTimestamp || author.photoLastModified);
  }

  get canEditMetadata(): boolean {
    const user = this.userService.getCurrentUser();
    return !!user?.permissions?.admin || !!user?.permissions?.canEditMetadata;
  }

  ngOnInit(): void {
    const authorId = Number(this.route.snapshot.paramMap.get('authorId'));
    const tabParam = this.route.snapshot.queryParamMap.get('tab');
    if (tabParam) {
      this.tab = tabParam;
    }
    this.loadAuthor(authorId);
  }

  ngAfterViewChecked(): void {
    const descriptionContent = this.descriptionContentRef();
    this.updateDescriptionOverflow(descriptionContent?.nativeElement);
  }

  updateDescriptionOverflow(element?: Pick<HTMLElement, 'scrollHeight' | 'clientHeight'>): void {
    if (!this.isExpanded && element) {
      this.isOverflowing = element.scrollHeight > element.clientHeight;
    }
  }

  toggleExpand(): void {
    this.isExpanded = !this.isExpanded;
  }

  onPhotoError(): void {
    this.hasPhoto = false;
  }

  onAuthorUpdated(updatedAuthor: AuthorDetails): void {
    this.authorState.set(updatedAuthor);
    this.hasPhoto = true;
    this.photoTimestamp = updatedAuthor.photoLastModified ?? Date.now();
    this.authorService.patchAuthorInCache(updatedAuthor.id, {
      name: updatedAuthor.name,
      asin: updatedAuthor.asin,
      hasPhoto: true,
      photoLastModified: this.photoTimestamp,
    });
  }

  quickMatch(): void {
    const author = this.author();
    if (!author || this.quickMatching) return;
    this.quickMatching = true;
    this.authorService.quickMatchAuthor(author.id).subscribe({
      next: (matched) => {
        this.onAuthorUpdated(matched);
        this.quickMatching = false;
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('authorBrowser.toast.quickMatchSuccessSummary'),
          detail: this.t.translate('authorBrowser.toast.quickMatchSuccessDetail')
        });
      },
      error: () => {
        this.quickMatching = false;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('authorBrowser.toast.quickMatchFailedSummary'),
          detail: this.t.translate('authorBrowser.toast.quickMatchFailedDetail')
        });
      }
    });
  }

  onBooksScroll(): void {
    if (this.isFetchingNextPage() || !this.hasNextBookPage) return;

    const loaded = this.authorBooks().length;
    const lastVisibleIndex = this.virtualGrid.virtualizer.getVirtualItems().at(-1)?.index ?? 0;
    const preloadItems = Math.max(this.virtualGrid.gridColumns() * 4, 12);
    if (lastVisibleIndex >= loaded - preloadItems) {
      const authorName = this.author()?.name;
      if (authorName) {
        this.loadAuthorBooks(authorName, this.nextBookPage);
      }
    }
  }

  private loadAuthor(authorId: number): void {
    this.authorService.getAuthorDetails(authorId).subscribe({
      next: (author) => {
        this.authorState.set(author);
        this.photoTimestamp = author.photoLastModified ?? 0;
        this.loading.set(false);
        this.pageTitle.setPageTitle(author.name);
        this.loadAuthorBooks(author.name, 0);
      },
      error: () => {
        this.loading.set(false);
        this.booksLoading.set(false);
      }
    });
  }

  private loadAuthorBooks(authorName: string, page: number): void {
    if (page === 0) {
      this.booksLoading.set(true);
      this.booksLoadError.set(false);
    } else {
      this.isFetchingNextPage.set(true);
    }

    this.authorService.getAuthorBooks(authorName, page).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => {
        this.booksLoading.set(false);
        this.isFetchingNextPage.set(false);
      })
    ).subscribe({
      next: response => {
        this.authorBooksState.update(current => page === 0
          ? response.content
          : [...current, ...response.content]);
        this.authorBookCount.set(response.totalElements);
        this.hasNextBookPage = response.hasNext;
        this.nextBookPage = response.page + 1;
      },
      error: () => {
        if (page === 0) {
          this.authorBooksState.set([]);
          this.authorBookCount.set(0);
          this.booksLoadError.set(true);
        }
      }
    });
  }
}
