import {Component, EventEmitter, inject, Input, OnInit, Output, Renderer2} from '@angular/core';
import {DecimalPipe, DOCUMENT} from '@angular/common';
import {TranslocoDirective} from '@jsverse/transloco';
import {ReaderFlow, ReaderState, ReaderStateService} from '../state/reader-state.service';
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

  private initialState!: ReaderState;
  private initialAnnotationColor = '#FFFF00';
  private initialUsesGlobalSettings = false;
  private pendingUsesGlobalSettings = false;

  onOverlayClick(event: Event): void {
    if ((event.target as HTMLElement).classList.contains('dialog-overlay')) {
      this.cancel();
    }
  }

  activeTab: 'theme' | 'typography' | 'layout' = 'theme';

  selectedAnnotationColor: string = '#FFFF00';

  annotationColors: AnnotationColor[] = [
    {name: 'yellow', value: '#FFFF00', label: 'Yellow'},
    {name: 'green', value: '#90EE90', label: 'Green'},
    {name: 'blue', value: '#87CEEB', label: 'Blue'},
    {name: 'pink', value: '#FFB6C1', label: 'Pink'},
    {name: 'orange', value: '#FFD580', label: 'Orange'}
  ];

  private readonly customFontService = inject(EpubCustomFontService);
  private readonly renderer = inject(Renderer2);
  private readonly document = inject(DOCUMENT);

  ngOnInit() {
    this.customFontService.injectCustomFontsStylesheet(this.renderer, this.document);
    this.selectedAnnotationColor = this.getSelectedAnnotationColor();
    this.initialAnnotationColor = this.selectedAnnotationColor;
    this.initialState = this.stateService.getStateSnapshot();
    this.initialUsesGlobalSettings = this.stateService.usesGlobalSettings();
    this.pendingUsesGlobalSettings = this.initialUsesGlobalSettings;
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
    return this.pendingUsesGlobalSettings;
  }

  toggleGlobalSettings(): void {
    this.pendingUsesGlobalSettings = !this.pendingUsesGlobalSettings;
  }

  setFontFamily(value: string | null) {
    this.stateService.setFontFamily(value);
  }

  increaseFontSize() {
    this.stateService.updateFontSize(1);
  }

  decreaseFontSize() {
    this.stateService.updateFontSize(-1);
  }

  increaseLineHeight() {
    this.stateService.updateLineHeight(0.1);
  }

  decreaseLineHeight() {
    this.stateService.updateLineHeight(-0.1);
  }

  increaseMaxColumnCount() {
    this.stateService.updateMaxColumnCount(1);
  }

  decreaseMaxColumnCount() {
    this.stateService.updateMaxColumnCount(-1);
  }

  setGap(value: number) {
    const delta = value - this.state.gap;
    this.stateService.updateGap(delta);
  }

  setBackgroundSaturation(value: number) {
    this.stateService.setBackgroundSaturation(value);
  }

  setBackgroundTransparency(value: number) {
    this.stateService.setBackgroundTransparency(value);
  }

  toggleJustify() {
    this.stateService.toggleJustify();
  }

  toggleHyphenate() {
    this.stateService.toggleHyphenate();
  }

  increaseMaxInlineSize() {
    this.stateService.updateMaxInlineSize(40);
  }

  decreaseMaxInlineSize() {
    this.stateService.updateMaxInlineSize(-40);
  }

  increaseMaxBlockSize() {
    this.stateService.updateMaxBlockSize(60);
  }

  decreaseMaxBlockSize() {
    this.stateService.updateMaxBlockSize(-60);
  }

  increasePageMargin() {
    this.stateService.updatePageMargin(8);
  }

  decreasePageMargin() {
    this.stateService.updatePageMargin(-8);
  }

  toggleFullWidth() {
    this.stateService.toggleFullWidth();
  }

  toggleDarkMode() {
    this.stateService.toggleDarkMode();
  }

  onThemeChange(themeName: string) {
    this.stateService.setThemeByName(themeName);
  }

  setFlow(flow: ReaderFlow) {
    this.stateService.setFlow(flow);
    this.viewManager.setFlow(flow);
  }

  setAnnotationColor(color: string): void {
    this.selectedAnnotationColor = color;
  }

  cancel(): void {
    this.stateService.restoreState(this.initialState);
    this.viewManager.setFlow(this.initialState.flow);
    this.selectedAnnotationColor = this.initialAnnotationColor;
    this.closed.emit();
  }

  save(): void {
    localStorage.setItem('selectedAnnotationColor', this.selectedAnnotationColor);
    if (this.pendingUsesGlobalSettings !== this.initialUsesGlobalSettings) {
      this.stateService.setGlobalSettings(this.pendingUsesGlobalSettings, this.bookId);
    } else {
      this.stateService.persistSettings(this.bookId);
    }
    this.closed.emit();
  }

  getSelectedAnnotationColor(): string {
    const stored = localStorage.getItem('selectedAnnotationColor');
    return stored || this.selectedAnnotationColor;
  }
}
