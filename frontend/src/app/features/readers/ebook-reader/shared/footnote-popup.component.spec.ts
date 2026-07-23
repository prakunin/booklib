import {ComponentFixture, TestBed} from '@angular/core/testing';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {getTranslocoModule} from '../../../../core/testing/transloco-testing';
import {FootnotePopupComponent} from './footnote-popup.component';

describe('FootnotePopupComponent', () => {
  let fixture: ComponentFixture<FootnotePopupComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FootnotePopupComponent, getTranslocoModule()]
    }).compileComponents();

    fixture = TestBed.createComponent(FootnotePopupComponent);
  });

  function render(visible: boolean, html = '<p>Note text</p>'): void {
    fixture.componentRef.setInput('visible', visible);
    fixture.componentRef.setInput('html', html);
    fixture.componentRef.setInput('position', {x: 100, y: 200});
    fixture.componentRef.setInput('showBelow', true);
    fixture.detectChanges();
  }

  it('renders nothing while hidden', () => {
    render(false);

    expect(fixture.nativeElement.querySelector('.footnote-popup')).toBeNull();
    expect(fixture.nativeElement.querySelector('.footnote-backdrop')).toBeNull();
  });

  it('renders the note content when visible', () => {
    render(true);

    const body = fixture.nativeElement.querySelector('.footnote-body');
    expect(body.textContent).toContain('Note text');
  });

  it('positions the card from the supplied coordinates', () => {
    render(true);

    const popup = fixture.nativeElement.querySelector('.footnote-popup') as HTMLElement;
    expect(popup.style.left).toBe('100px');
    expect(popup.style.top).toBe('200px');
    expect(popup.classList.contains('show-below')).toBe(true);
  });

  it('paints the card in the supplied page theme', () => {
    fixture.componentRef.setInput('visible', true);
    fixture.componentRef.setInput('html', '<p>Note</p>');
    fixture.componentRef.setInput('background', '#f1e8d0');
    fixture.componentRef.setInput('foreground', '#5b4636');
    fixture.componentRef.setInput('dark', false);
    fixture.detectChanges();

    const popup = fixture.nativeElement.querySelector('.footnote-popup') as HTMLElement;
    expect(popup.style.getPropertyValue('--footnote-bg')).toBe('#f1e8d0');
    expect(popup.style.getPropertyValue('--footnote-fg')).toBe('#5b4636');
    expect(popup.classList.contains('is-dark')).toBe(false);
  });

  it('emits dismissed when the close button is clicked', () => {
    render(true);
    const dismissed = vi.fn();
    fixture.componentInstance.dismissed.subscribe(dismissed);

    fixture.nativeElement.querySelector('.footnote-close').click();

    expect(dismissed).toHaveBeenCalled();
  });

  it('emits dismissed when the backdrop is clicked', () => {
    render(true);
    const dismissed = vi.fn();
    fixture.componentInstance.dismissed.subscribe(dismissed);

    fixture.nativeElement.querySelector('.footnote-backdrop').click();

    expect(dismissed).toHaveBeenCalled();
  });

  it('emits openFullRequested from the open-in-full button', () => {
    render(true);
    const openFull = vi.fn();
    fixture.componentInstance.openFullRequested.subscribe(openFull);

    fixture.nativeElement.querySelector('.footnote-open-full').click();

    expect(openFull).toHaveBeenCalled();
  });
});
