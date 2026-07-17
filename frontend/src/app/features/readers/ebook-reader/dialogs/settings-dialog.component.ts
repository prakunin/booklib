import {Component, EventEmitter, inject, Input, OnInit, Output, Renderer2} from '@angular/core';
import {DecimalPipe, DOCUMENT} from '@angular/common';
import {TranslocoDirective} from '@jsverse/transloco';
import {ReaderStateService} from '../state/reader-state.service';
import {ReaderViewManagerService} from '../core/view-manager.service';
import {EpubCustomFontService} from '../features/fonts/custom-font.service';

interface AnnotationColor {
  name: string;
  value: string;
  label: string;
}

@Component({
  selector: 'app-settings-dialog',
  standalone: true,
  imports: [DecimalPipe, TranslocoDirective],
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.scss']
})
export class ReaderSettingsDialogComponent implements OnInit {
  @Input() stateService!: ReaderStateService;
  @Input() viewManager!: ReaderViewManagerService;
  @Input() bookId!: number;

  @Output() closed = new EventEmitter<void>();

  activeTab: 'theme' | 'typography' | 'layout' = 'theme';

  selectedAnnotationColor: string = '#FFFF00';

  annotationColors: AnnotationColor[] = [
    {name: 'yellow', value: '#FFFF00', label: 'Yellow'},
    {name: 'green', value: '#90EE90', label: 'Green'},
    {name: 'blue', value: '#87CEEB', label: 'Blue'},
    {name: 'pink', value: '#FFB6C1', label: 'Pink'},
    {name: 'orange', value: '#FFD580', label: 'Orange'}
  ];

  private customFontService = inject(EpubCustomFontService);
  private renderer = inject(Renderer2);
  private document = inject(DOCUMENT);

  ngOnInit() {
    this.customFontService.injectCustomFontsStylesheet(this.renderer, this.document);
    this.selectedAnnotationColor = this.getSelectedAnnotationColor();
  }

  getFontFamilyForPreview(fontValue: string): string {
    return this.customFontService.getFontFamilyForPreview(fontValue);
  }

  get state() {
    return this.stateService.state();
  }

  get themes() {
    return this.stateService.themes;
  }

  get fonts() {
    return this.stateService.fonts();
  }

  get usesGlobalSettings(): boolean {
    return this.stateService.usesGlobalSettings();
  }

  private syncSettingsToBackend() {
    this.stateService.persistSettings(this.bookId);
  }

  toggleGlobalSettings(): void {
    this.stateService.setGlobalSettings(!this.usesGlobalSettings, this.bookId);
  }

  setFontFamily(value: string | null) {
    this.stateService.setFontFamily(value);
    this.syncSettingsToBackend();
  }

  increaseFontSize() {
    this.stateService.updateFontSize(1);
    this.syncSettingsToBackend();
  }

  decreaseFontSize() {
    this.stateService.updateFontSize(-1);
    this.syncSettingsToBackend();
  }

  increaseLineHeight() {
    this.stateService.updateLineHeight(0.1);
    this.syncSettingsToBackend();
  }

  decreaseLineHeight() {
    this.stateService.updateLineHeight(-0.1);
    this.syncSettingsToBackend();
  }

  increaseMaxColumnCount() {
    this.stateService.updateMaxColumnCount(1);
    this.syncSettingsToBackend();
  }

  decreaseMaxColumnCount() {
    this.stateService.updateMaxColumnCount(-1);
    this.syncSettingsToBackend();
  }

  setGap(value: number) {
    const delta = value - this.state.gap;
    this.stateService.updateGap(delta);
    this.syncSettingsToBackend();
  }

  toggleJustify() {
    this.stateService.toggleJustify();
    this.syncSettingsToBackend();
  }

  toggleHyphenate() {
    this.stateService.toggleHyphenate();
    this.syncSettingsToBackend();
  }

  increaseMaxInlineSize() {
    this.stateService.updateMaxInlineSize(40);
    this.syncSettingsToBackend();
  }

  decreaseMaxInlineSize() {
    this.stateService.updateMaxInlineSize(-40);
    this.syncSettingsToBackend();
  }

  increaseMaxBlockSize() {
    this.stateService.updateMaxBlockSize(60);
    this.syncSettingsToBackend();
  }

  decreaseMaxBlockSize() {
    this.stateService.updateMaxBlockSize(-60);
    this.syncSettingsToBackend();
  }

  toggleFullWidth() {
    this.stateService.toggleFullWidth();
    this.syncSettingsToBackend();
  }

  toggleDarkMode() {
    this.stateService.toggleDarkMode();
    this.syncSettingsToBackend();
  }

  onThemeChange(themeName: string) {
    this.stateService.setThemeByName(themeName);
    this.syncSettingsToBackend();
  }

  setFlow(flow: 'paginated' | 'scrolled') {
    this.stateService.setFlow(flow);
    this.viewManager.getRenderer()?.setAttribute?.('flow', flow);
    this.syncSettingsToBackend();
  }

  setAnnotationColor(color: string): void {
    this.selectedAnnotationColor = color;
    localStorage.setItem('selectedAnnotationColor', color);
  }

  getSelectedAnnotationColor(): string {
    const stored = localStorage.getItem('selectedAnnotationColor');
    return stored || this.selectedAnnotationColor;
  }
}
