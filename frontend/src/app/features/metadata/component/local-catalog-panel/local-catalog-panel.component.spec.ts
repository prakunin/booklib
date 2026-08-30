import {ComponentFixture, TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {createQueryClientHarness, flushQueryAsync} from '../../../../core/testing/query-testing';
import {getTranslocoModule} from '../../../../core/testing/transloco-testing';
import {BookDialogHelperService} from '../../../book/components/book-browser/book-dialog-helper.service';
import {LocalCatalogBookView} from '../../model/local-catalog.model';
import {LocalCatalogPanelComponent} from './local-catalog-panel.component';

describe('LocalCatalogPanelComponent', () => {
  let fixture: ComponentFixture<LocalCatalogPanelComponent>;
  let httpMock: HttpTestingController;
  let openEnrichmentDialog: ReturnType<typeof vi.fn>;

  function emptyView(overrides: Partial<LocalCatalogBookView> = {}): LocalCatalogBookView {
    return {
      available: true,
      authors: [],
      reviewCount: 0,
      reviews: [],
      containingCompilations: [],
      compilationParts: [],
      authorBios: [],
      fieldsFromCatalog: [],
      ...overrides,
    };
  }

  async function respondWith(view: LocalCatalogBookView, bookId = 41): Promise<void> {
    fixture.componentRef.setInput('bookId', bookId);
    fixture.detectChanges();
    await flushQueryAsync();
    httpMock.expectOne(request => request.url.endsWith(`/api/v1/enrichment/local-catalog/books/${bookId}`))
      .flush(view);
    await flushQueryAsync();
    fixture.detectChanges();
  }

  beforeEach(() => {
    const harness = createQueryClientHarness();
    openEnrichmentDialog = vi.fn(() => Promise.resolve(null));
    TestBed.configureTestingModule({
      imports: [LocalCatalogPanelComponent, getTranslocoModule()],
      providers: [
        ...harness.providers,
        {provide: BookDialogHelperService, useValue: {openEnrichmentDialog}},
      ],
    });
    fixture = TestBed.createComponent(LocalCatalogPanelComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('renders the catalog key and annotation when the catalog holds an entry', async () => {
    await respondWith(emptyView({
      sourceArchive: 'f.fb2-352350-355443.zip',
      sourceArchiveEntry: '354924.fb2',
      description: 'Аннотация из каталога',
    }));

    expect(fixture.componentInstance.catalogKey()).toBe('f.fb2-352350-355443.zip#354924.fb2');
    expect(fixture.componentInstance.empty()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Аннотация из каталога');
  });

  it('separates a catalog that holds nothing for the book from one that cannot be read at all', async () => {
    await respondWith(emptyView({sourceArchive: 'a.zip', sourceArchiveEntry: '1.fb2'}));

    expect(fixture.componentInstance.empty()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('The local catalog holds no entry for this book.');
  });

  it('reports an unavailable catalog without claiming the book has no entry', async () => {
    await respondWith(emptyView({available: false}));

    expect(fixture.componentInstance.empty()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('has no local catalog');
  });

  it('marks a field as in use only when its recorded provenance is the catalog', async () => {
    await respondWith(emptyView({
      description: 'annotation',
      language: 'ru',
      fieldsFromCatalog: ['DESCRIPTION'],
    }));

    expect(fixture.componentInstance.applied('DESCRIPTION')).toBe(true);
    expect(fixture.componentInstance.applied('LANGUAGE')).toBe(false);
  });

  it('says how many reviews it is showing when the catalog holds more than the response carries', async () => {
    await respondWith(emptyView({
      reviewCount: 120,
      reviews: [{body: 'first', reviewerName: 'reader', postedAt: '2010-01-01T00:00:00Z'}],
    }));

    expect(fixture.nativeElement.textContent).toContain('Showing 1 of 120.');
  });

  it('opens enrichment narrowed to the catalog sources and set to replace', async () => {
    await respondWith(emptyView({description: 'annotation'}), 77);

    await fixture.componentInstance.applyFromCatalog();

    expect(openEnrichmentDialog).toHaveBeenCalledWith(new Set([77]), {
      steps: ['LOCAL_CATALOG', 'LOCAL_LANGUAGE', 'LOCAL_COMPILATION', 'REVIEWS', 'AUTHOR_BIO'],
      writePolicy: 'AUTO',
    });
  });
});
