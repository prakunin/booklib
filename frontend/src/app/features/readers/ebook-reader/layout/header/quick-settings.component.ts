import {Component, EventEmitter, Input, Output} from '@angular/core';
import {DecimalPipe} from '@angular/common';
import {TranslocoDirective} from '@jsverse/transloco';
import {ReaderStateService} from '../../state/reader-state.service';
import {ReaderIconComponent} from '../../shared/icon.component';

@Component({
  selector: 'app-reader-quick-settings',
  standalone: true,
  imports: [DecimalPipe, TranslocoDirective, ReaderIconComponent],
  templateUrl: './quick-settings.component.html',
  styleUrls: ['./quick-settings.component.scss']
})
export class ReaderQuickSettingsComponent {
  @Input() stateService!: ReaderStateService;
  @Input() bookId!: number;
  @Output() closed = new EventEmitter<void>();
  @Output() openFullSettings = new EventEmitter<void>();

  get state() {
    return this.stateService.state();
  }

  get isDarkMode(): boolean {
    return this.state.isDark;
  }

  private syncSettingsToBackend(): void {
    this.stateService.persistSettings(this.bookId);
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

  increaseLineHeight(): void {
    this.stateService.updateLineHeight(0.1);
    this.syncSettingsToBackend();
  }

  decreaseLineHeight(): void {
    this.stateService.updateLineHeight(-0.1);
    this.syncSettingsToBackend();
  }

  onOpenFullSettings(): void {
    this.closed.emit();
    this.openFullSettings.emit();
  }

  onOverlayClick(event: Event): void {
    if ((event.target as HTMLElement).classList.contains('quick-settings-overlay')) {
      this.closed.emit();
    }
  }
}
