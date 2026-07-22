import {ComponentFixture, TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {of, throwError} from 'rxjs';
import {Router} from '@angular/router';

import {getTranslocoModule} from '../../../../core/testing/transloco-testing';
import {UrlHelperService} from '../../../../shared/service/url-helper.service';
import {AuthorService} from '../../../author-browser/service/author.service';
import {ReaderBookMetadataDialogComponent} from './metadata-dialog.component';

describe('ReaderBookMetadataDialogComponent', () => {
  let fixture: ComponentFixture<ReaderBookMetadataDialogComponent>;
  let component: ReaderBookMetadataDialogComponent;
  let urlHelperService: {getCoverUrl: ReturnType<typeof vi.fn>};
  let authorService: {getAuthorByName: ReturnType<typeof vi.fn>};
  let router: {navigate: ReturnType<typeof vi.fn>};

  beforeEach(async () => {
    urlHelperService = {
      getCoverUrl: vi.fn(() => '/covers/12?v=1'),
    };
    authorService = {
      getAuthorByName: vi.fn(),
    };
    router = {
      navigate: vi.fn(() => Promise.resolve(true)),
    };

    await TestBed.configureTestingModule({
      imports: [ReaderBookMetadataDialogComponent, getTranslocoModule()],
      providers: [
        {provide: UrlHelperService, useValue: urlHelperService},
        {provide: AuthorService, useValue: authorService},
        {provide: Router, useValue: router},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReaderBookMetadataDialogComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('exposes metadata and builds a cover url when a book id is available', () => {
    component.book = {
      id: 12,
      metadata: {
        title: 'Atlas',
        coverUpdatedOn: '2024-01-02',
      },
    } as never;

    expect(component.metadata?.title).toBe('Atlas');
    expect(component.bookCoverUrl).toBe('/covers/12?v=1');
    expect(urlHelperService.getCoverUrl).toHaveBeenCalledWith(12, '2024-01-02');
  });

  it('formats missing and present metadata fields safely', () => {
    expect(component.formatFileSize(undefined)).toBeTruthy();
    expect(component.formatFileSize(512)).toBe('512.0 KB');
    expect(component.formatFileSize(2048)).toBe('2.00 MB');
    expect(component.formatDate(undefined)).toBeTruthy();
    expect(component.formatDate('2024-06-15')).toContain('2024');
  });

  it('returns the raw date when parsing falls back after an invalid date string', () => {
    expect(component.formatDate('not-a-date')).toBe('Invalid Date');
  });

  it('renders every author as a separate link-style button', () => {
    component.book = {
      id: 12,
      metadata: {authors: ['One', 'Two']},
    } as never;

    fixture.detectChanges();

    const authorButtons = fixture.nativeElement.querySelectorAll('.author-link') as NodeListOf<HTMLButtonElement>;
    expect([...authorButtons].map(button => button.textContent?.trim())).toEqual(['One', 'Two']);
  });

  it('closes the reader dialog and navigates to the author page', () => {
    authorService.getAuthorByName.mockReturnValue(of({id: 42}));
    const closed = vi.fn();
    component.closed.subscribe(closed);

    component.goToAuthorBooks('One');

    expect(authorService.getAuthorByName).toHaveBeenCalledWith('One');
    expect(closed).toHaveBeenCalledOnce();
    expect(router.navigate).toHaveBeenCalledWith(['/author', 42]);
  });

  it('falls back to the filtered book browser when an author record is unavailable', () => {
    authorService.getAuthorByName.mockReturnValue(throwError(() => new Error('not found')));

    component.goToAuthorBooks('One & Two');

    expect(router.navigate).toHaveBeenCalledWith(['/all-books'], {
      queryParams: {
        view: 'grid',
        sort: 'title',
        direction: 'asc',
        sidebar: true,
        filter: 'author:One%20%26%20Two'
      }
    });
  });
});
