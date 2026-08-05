import {Component} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {describe, expect, it, beforeEach} from 'vitest';

import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {MetadataFieldSources} from '../../metadata/metadata-field-source';
import {MetadataSourceBadgeComponent} from './metadata-source-badge.component';

@Component({
  standalone: true,
  imports: [MetadataSourceBadgeComponent],
  template: `<app-metadata-source-badge [field]="field" [sources]="sources" />`,
})
class HostComponent {
  field = 'title';
  sources: MetadataFieldSources | undefined = undefined;
}

describe('MetadataSourceBadgeComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostComponent, getTranslocoModule()],
    }).compileComponents();

    fixture = TestBed.createComponent(HostComponent);
  });

  function tagElement(): HTMLElement | null {
    return fixture.nativeElement.querySelector('app-tag');
  }

  it('names the provider that filled the field', () => {
    fixture.componentInstance.sources = {TITLE: 'GoodReads'};
    fixture.detectChanges();

    expect(tagElement()?.textContent?.trim()).toBe('GoodReads');
  });

  it('renders no element at all for a field with no recorded source', () => {
    fixture.componentInstance.sources = {ISBN_13: 'GoodReads'};
    fixture.detectChanges();

    expect(tagElement()).toBeNull();
  });

  it('renders no element at all when the book has no sources whatsoever', () => {
    fixture.componentInstance.sources = {};
    fixture.detectChanges();

    expect(tagElement()).toBeNull();
  });

  it('renders no element at all when the endpoint attached nothing', () => {
    fixture.componentInstance.sources = undefined;
    fixture.detectChanges();

    expect(tagElement()).toBeNull();
  });

  it('renders no element at all for a field that has no provenance by design', () => {
    fixture.componentInstance.field = 'authors';
    fixture.componentInstance.sources = {TITLE: 'GoodReads'};
    fixture.detectChanges();

    expect(tagElement()).toBeNull();
  });

  it('shows a human label for a provider whose enum name is not one', () => {
    fixture.componentInstance.sources = {TITLE: 'FlibustaLocal'};
    fixture.detectChanges();

    expect(tagElement()?.textContent?.trim()).toBe('Local catalog');
    expect(tagElement()?.textContent).not.toContain('FlibustaLocal');
  });

  it('explains what the badge means rather than leaving a bare provider name', () => {
    fixture.componentInstance.sources = {TITLE: 'FlibustaLocal'};
    fixture.detectChanges();

    const badge = fixture.nativeElement.querySelector('.metadata-source-badge');
    expect(badge?.getAttribute('aria-label')).toBe('Filled in from Local catalog');
  });
});
