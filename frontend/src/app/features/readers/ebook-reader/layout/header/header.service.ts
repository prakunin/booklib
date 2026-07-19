import {computed, inject, Injectable, signal} from '@angular/core';
import {Location} from '@angular/common';
import {Subject} from 'rxjs';
import {ReaderStateService} from '../../state/reader-state.service';
import {ReaderSidebarService} from '../sidebar/sidebar.service';
import {ReaderLeftSidebarService} from '../panel/panel.service';

@Injectable()
export class ReaderHeaderService {
  private readonly stateService = inject(ReaderStateService);
  private readonly sidebarService = inject(ReaderSidebarService);
  private readonly leftSidebarService = inject(ReaderLeftSidebarService);
  private readonly location = inject(Location);

  private bookId!: number;
  private readonly _bookTitle = signal('');
  readonly bookTitle = this._bookTitle.asReadonly();

  private readonly _forceVisible = signal(false);
  readonly forceVisible = this._forceVisible.asReadonly();

  private readonly _isCurrentCfiBookmarked = signal(false);
  readonly isCurrentCfiBookmarked = this._isCurrentCfiBookmarked.asReadonly();

  private readonly _showControls = new Subject<void>();
  private readonly _showMetadata = new Subject<void>();
  private readonly _toggleFullscreen = new Subject<void>();
  private readonly _showShortcutsHelp = new Subject<void>();
  private readonly _isFullscreen = signal(false);
  readonly isFullscreen = this._isFullscreen.asReadonly();
  showControls$ = this._showControls.asObservable();
  showMetadata$ = this._showMetadata.asObservable();
  toggleFullscreen$ = this._toggleFullscreen.asObservable();
  showShortcutsHelp$ = this._showShortcutsHelp.asObservable();
  readonly theme = computed(() => this.stateService.state().theme);
  readonly justify = computed(() => this.stateService.state().justify);

  initialize(bookId: number, title: string): void {
    this.bookId = bookId;
    this._bookTitle.set(title);
  }

  setForceVisible(visible: boolean): void {
    this._forceVisible.set(visible);
  }

  setCurrentCfiBookmarked(bookmarked: boolean): void {
    this._isCurrentCfiBookmarked.set(bookmarked);
  }

  openSidebar(): void {
    this.sidebarService.open();
  }

  openLeftSidebar(tab?: 'search' | 'notes'): void {
    this.leftSidebarService.open(tab);
  }

  createBookmark(): void {
    this.sidebarService.toggleBookmark();
  }

  openControls(): void {
    this._showControls.next();
  }

  openMetadata(): void {
    this._showMetadata.next();
  }

  toggleFullscreen(): void {
    this._toggleFullscreen.next();
  }

  setFullscreen(isFullscreen: boolean): void {
    this._isFullscreen.set(isFullscreen);
  }

  showShortcutsHelp(): void {
    this._showShortcutsHelp.next();
  }

  close(): void {
    this.location.back();
  }

  toggleDarkMode(): void {
    this.stateService.toggleDarkMode();
    this.syncSettingsToBackend();
  }

  increaseFontSize(): void {
    this.stateService.updateFontSize(1);
    this.syncSettingsToBackend();
  }

  decreaseFontSize(): void {
    this.stateService.updateFontSize(-1);
    this.syncSettingsToBackend();
  }

  toggleJustify(): void {
    this.stateService.toggleJustify();
    this.syncSettingsToBackend();
  }

  private syncSettingsToBackend(): void {
    this.stateService.persistSettings(this.bookId);
  }

  reset(): void {
    this._forceVisible.set(false);
    this._isCurrentCfiBookmarked.set(false);
    this._isFullscreen.set(false);
    this._bookTitle.set('');
  }
}
