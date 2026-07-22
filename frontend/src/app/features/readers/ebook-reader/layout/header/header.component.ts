import {Component, inject} from '@angular/core';
import {TranslocoDirective} from '@jsverse/transloco';
import {ReaderHeaderService} from './header.service';
import {ReaderIconComponent} from '../../shared/icon.component';
import {Router} from '@angular/router';

@Component({
  selector: 'app-reader-header',
  standalone: true,
  imports: [TranslocoDirective, ReaderIconComponent],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.scss']
})
export class ReaderHeaderComponent {
  private readonly headerService = inject(ReaderHeaderService);
  private readonly router = inject(Router);

  readonly isCurrentCfiBookmarked = this.headerService.isCurrentCfiBookmarked;
  readonly isFullscreen = this.headerService.isFullscreen;
  readonly bookTitle = this.headerService.bookTitle;
  readonly theme = this.headerService.theme;
  readonly justify = this.headerService.justify;
  overflowOpen = false;

  onShowChapters(): void {
    this.headerService.openSidebar();
  }

  onOpenNotes(): void {
    this.headerService.openLeftSidebar('notes');
  }

  onOpenSearch(): void {
    this.headerService.openLeftSidebar('search');
  }

  onCreateBookmark(): void {
    this.headerService.createBookmark();
  }

  onShowControls(): void {
    this.headerService.openControls();
  }

  onToggleFullscreen(): void {
    this.headerService.toggleFullscreen();
  }

  onShowHelp(): void {
    this.headerService.showShortcutsHelp();
  }

  onDecreaseFontSize(): void {
    this.headerService.decreaseFontSize();
  }

  onIncreaseFontSize(): void {
    this.headerService.increaseFontSize();
  }

  onToggleJustify(): void {
    this.headerService.toggleJustify();
  }

  onClose(): void {
    if (globalThis.history.length <= 2) {
      this.router.navigate(['/dashboard']);
    } else {
      this.headerService.close();
    }
  }
}
