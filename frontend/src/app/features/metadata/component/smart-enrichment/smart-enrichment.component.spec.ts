import {ComponentFixture, TestBed} from '@angular/core/testing';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {Subject, of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {getTranslocoModule} from '../../../../core/testing/transloco-testing';
import {SmartEnrichmentComponent} from './smart-enrichment.component';
import {SmartEnrichmentService} from '../../service/smart-enrichment.service';
import {SmartEnrichmentEvent} from '../../model/smart-enrichment.model';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';

describe('SmartEnrichmentComponent', () => {
  let fixture: ComponentFixture<SmartEnrichmentComponent>;
  let component: SmartEnrichmentComponent;
  let events: Subject<SmartEnrichmentEvent>;
  let updateBookMetadata: ReturnType<typeof vi.fn>;
  let dialogRef: {close: ReturnType<typeof vi.fn>};

  const completed = (overrides: Partial<SmartEnrichmentEvent> = {}): SmartEnrichmentEvent => ({
    stage: 'COMPLETED',
    message: null,
    identity: {
      originalTitle: 'Journal de voyage',
      originalAuthor: 'Michel de Montaigne',
      originalLanguage: 'fr',
      editionTitle: null,
      editionAuthor: null,
      editionLanguage: null,
      firstPublishedYear: 1774,
      goodreadsUrl: 'https://www.goodreads.com/book/show/104595',
      reportedRating: 3.68,
      description: 'Аннотация.',
      descriptionLanguage: 'ru',
      descriptionSourceUrl: 'https://example.org/book',
      publisher: null,
      publishedDate: null,
      isbn13: null,
      isbn10: null,
      pageCount: null,
      seriesName: null,
      seriesNumber: null,
      seriesTotal: null,
      genres: null,
      sources: ['https://www.goodreads.com/book/show/104595'],
    },
    ratingVerification: {reported: 3.68, verified: 3.68, agrees: true},
    proposals: [
      {field: 'description', currentValue: null, proposedValue: 'Аннотация.', source: 'Agent (ru)', sourceUrl: 'https://example.org/book', locked: false},
      {field: 'goodreadsRating', currentValue: null, proposedValue: '3.68', source: 'Goodreads (verified)', sourceUrl: null, locked: false},
    ],
    ...overrides,
  });

  beforeEach(async () => {
    events = new Subject<SmartEnrichmentEvent>();
    updateBookMetadata = vi.fn(() => of({}));
    dialogRef = {close: vi.fn()};

    await TestBed.configureTestingModule({
      imports: [SmartEnrichmentComponent, getTranslocoModule()],
      providers: [
        MessageService,
        {provide: DynamicDialogConfig, useValue: {data: {bookId: 7}}},
        {provide: DynamicDialogRef, useValue: dialogRef},
        {provide: SmartEnrichmentService, useValue: {enrich: () => events.asObservable()}},
        {provide: BookMetadataManageService, useValue: {updateBookMetadata}},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SmartEnrichmentComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('starts in the resolving stage', () => {
    expect(component.stage()).toBe('RESOLVING');
    expect(component.running()).toBe(true);
  });

  it('follows the stages the backend reports', () => {
    events.next({stage: 'VERIFYING', message: null, identity: null, ratingVerification: null, proposals: []});
    expect(component.stage()).toBe('VERIFYING');

    events.next(completed());
    expect(component.stage()).toBe('COMPLETED');
    expect(component.running()).toBe(false);
    expect(component.proposals()).toHaveLength(2);
  });

  it('preselects every unlocked proposal', () => {
    events.next(completed());

    expect(component.isSelected('description')).toBe(true);
    expect(component.isSelected('goodreadsRating')).toBe(true);
  });

  // A locked field must be visible but never applied: manual edits outrank anything fetched.
  it('never selects a locked proposal', () => {
    events.next(completed({
      proposals: [
        {field: 'description', currentValue: 'Ручное', proposedValue: 'Новое', source: 'Agent', sourceUrl: null, locked: true},
      ],
    }));

    expect(component.isSelected('description')).toBe(false);
    expect(component.applicableProposals()).toHaveLength(0);
    expect(component.canApply()).toBe(false);
  });

  it('applies only the ticked fields, leaving the others untouched', () => {
    events.next(completed());
    component.toggleField('goodreadsRating');

    component.apply();

    expect(updateBookMetadata).toHaveBeenCalledWith(
      7,
      {metadata: {bookId: 7, description: 'Аннотация.'}, clearFlags: {}},
      false,
      'REPLACE_WHEN_PROVIDED'
    );
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('sends the rating as a number', () => {
    events.next(completed());
    component.toggleField('description');

    component.apply();

    expect(updateBookMetadata.mock.calls[0][1].metadata.goodreadsRating).toBe(3.68);
  });

  it('maps every known field to book metadata when applied directly', () => {
    events.next(completed({
      proposals: [
        {field: 'title', currentValue: null, proposedValue: 'Journal de voyage', source: 'Agent', sourceUrl: null, locked: false},
        {field: 'authors', currentValue: null, proposedValue: 'Michel de Montaigne', source: 'Agent', sourceUrl: null, locked: false},
        {field: 'publisher', currentValue: null, proposedValue: 'Азбука', source: 'Agent', sourceUrl: null, locked: false},
        {field: 'isbn13', currentValue: null, proposedValue: '9785389181205', source: 'Agent', sourceUrl: null, locked: false},
        {field: 'pageCount', currentValue: null, proposedValue: '384', source: 'Agent', sourceUrl: null, locked: false},
        {field: 'categories', currentValue: null, proposedValue: 'Travel, Memoir', source: 'Agent', sourceUrl: null, locked: false},
      ],
    }));

    component.apply();

    const metadata = updateBookMetadata.mock.calls[0][1].metadata;
    expect(metadata.title).toBe('Journal de voyage');
    expect(metadata.authors).toEqual(['Michel de Montaigne']);
    expect(metadata.publisher).toBe('Азбука');
    expect(metadata.isbn13).toBe('9785389181205');
    expect(metadata.pageCount).toBe(384);
    expect(metadata.categories).toEqual(['Travel', 'Memoir']);
  });

  it('does nothing when no field is ticked', () => {
    events.next(completed());
    component.toggleField('description');
    component.toggleField('goodreadsRating');

    component.apply();

    expect(updateBookMetadata).not.toHaveBeenCalled();
  });

  it('stays open and re-enables the button when applying fails', () => {
    updateBookMetadata.mockReturnValueOnce(throwError(() => ({error: {message: 'nope'}})));
    events.next(completed());

    component.apply();

    expect(component.applying()).toBe(false);
    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('shows the failure message when the work cannot be identified', () => {
    events.next({stage: 'FAILED', message: 'Could not identify the work', identity: null, ratingVerification: null, proposals: []});

    expect(component.stage()).toBe('FAILED');
    expect(component.errorMessage()).toBe('Could not identify the work');
  });

  it('reports a stream error as a failure', () => {
    events.error(new Error('boom'));

    expect(component.stage()).toBe('FAILED');
    expect(component.errorMessage()).toBe('boom');
  });

  // A run left going after the dialog closes would hold an agent process open for minutes.
  it('aborts the run when the dialog is destroyed', () => {
    expect(events.observed).toBe(true);

    fixture.destroy();

    expect(events.observed).toBe(false);
  });

  describe('form mode', () => {
    let formEvents: Subject<SmartEnrichmentEvent>;
    let formUpdate: ReturnType<typeof vi.fn>;
    let formDialogRef: {close: ReturnType<typeof vi.fn>};
    let formComponent: SmartEnrichmentComponent;

    beforeEach(async () => {
      formEvents = new Subject<SmartEnrichmentEvent>();
      formUpdate = vi.fn(() => of({}));
      formDialogRef = {close: vi.fn()};

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [SmartEnrichmentComponent, getTranslocoModule()],
        providers: [
          MessageService,
          {provide: DynamicDialogConfig, useValue: {data: {bookId: 9, applyMode: 'form'}}},
          {provide: DynamicDialogRef, useValue: formDialogRef},
          {provide: SmartEnrichmentService, useValue: {enrich: () => formEvents.asObservable()}},
          {provide: BookMetadataManageService, useValue: {updateBookMetadata: formUpdate}},
        ],
      }).compileComponents();

      const formFixture = TestBed.createComponent(SmartEnrichmentComponent);
      formComponent = formFixture.componentInstance;
      formFixture.detectChanges();
    });

    // The editor is already showing the form; writing straight to the DB would hide the change the
    // user asked to review. So form mode hands the proposals back and writes nothing.
    it('returns the ticked proposals and never touches the DB', () => {
      formEvents.next(completed());
      formComponent.toggleField('goodreadsRating');

      formComponent.apply();

      expect(formUpdate).not.toHaveBeenCalled();
      expect(formDialogRef.close).toHaveBeenCalledTimes(1);
      const result = formDialogRef.close.mock.calls[0][0];
      expect(result.proposals).toHaveLength(1);
      expect(result.proposals[0].field).toBe('description');
    });
  });

  describe('bulk mode', () => {
    let bulkFixture: ComponentFixture<SmartEnrichmentComponent>;
    let bulkComponent: SmartEnrichmentComponent;
    let bulkDialogRef: {close: ReturnType<typeof vi.fn>};
    let bulkUpdate: ReturnType<typeof vi.fn>;
    let enrich: ReturnType<typeof vi.fn>;
    let firstBookEvents: Subject<SmartEnrichmentEvent>;
    let secondBookEvents: Subject<SmartEnrichmentEvent>;

    beforeEach(async () => {
      firstBookEvents = new Subject<SmartEnrichmentEvent>();
      secondBookEvents = new Subject<SmartEnrichmentEvent>();
      bulkDialogRef = {close: vi.fn()};
      bulkUpdate = vi.fn(() => of({}));
      enrich = vi.fn((bookId: number) => bookId === 7
        ? firstBookEvents.asObservable()
        : secondBookEvents.asObservable());

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [SmartEnrichmentComponent, getTranslocoModule()],
        providers: [
          MessageService,
          {provide: DynamicDialogConfig, useValue: {data: {bookIds: [7, 8]}}},
          {provide: DynamicDialogRef, useValue: bulkDialogRef},
          {provide: SmartEnrichmentService, useValue: {enrich}},
          {provide: BookMetadataManageService, useValue: {updateBookMetadata: bulkUpdate}},
        ],
      }).compileComponents();

      bulkFixture = TestBed.createComponent(SmartEnrichmentComponent);
      bulkComponent = bulkFixture.componentInstance;
      bulkFixture.detectChanges();
    });

    it('starts only the first selected book and automatically applies before advancing', () => {
      expect(enrich).toHaveBeenCalledTimes(1);
      expect(enrich).toHaveBeenLastCalledWith(7);
      expect(firstBookEvents.observed).toBe(true);
      expect(secondBookEvents.observed).toBe(false);

      firstBookEvents.next(completed());

      expect(bulkUpdate).toHaveBeenCalledWith(
        7,
        expect.objectContaining({metadata: expect.objectContaining({bookId: 7})}),
        false,
        'REPLACE_WHEN_PROVIDED'
      );
      expect(firstBookEvents.observed).toBe(false);
      expect(enrich).toHaveBeenCalledTimes(2);
      expect(enrich).toHaveBeenLastCalledWith(8);
      expect(bulkComponent.currentIndex()).toBe(1);
      expect(bulkComponent.stage()).toBe('RESOLVING');
      expect(bulkComponent.proposals()).toEqual([]);
      expect(bulkDialogRef.close).not.toHaveBeenCalled();
    });

    it('keeps a stable bulk progress view while books are processed', () => {
      expect(bulkComponent.processedBooks()).toBe(0);
      expect(bulkComponent.progressPercent()).toBe(0);
      expect(bulkFixture.nativeElement.querySelector('.bulk-progress')).not.toBeNull();
      expect(bulkFixture.nativeElement.querySelector('.stage')).toBeNull();
      expect(bulkFixture.nativeElement.querySelector('.proposals')).toBeNull();

      firstBookEvents.next(completed());
      bulkFixture.detectChanges();

      expect(bulkComponent.processedBooks()).toBe(1);
      expect(bulkComponent.progressPercent()).toBe(50);
      expect(bulkFixture.nativeElement.querySelector('.bulk-progress')).not.toBeNull();
      expect(bulkFixture.nativeElement.querySelector('.proposals')).toBeNull();
    });

    it('continues automatically when a book cannot be identified', () => {
      firstBookEvents.next({
        stage: 'FAILED',
        message: 'Could not identify the work',
        identity: null,
        ratingVerification: null,
        proposals: [],
      });

      expect(firstBookEvents.observed).toBe(false);
      expect(enrich).toHaveBeenLastCalledWith(8);
      expect(bulkComponent.currentIndex()).toBe(1);
      expect(bulkComponent.failedBooks()).toBe(1);
    });

    it('finishes the queue automatically after the last book is applied', () => {
      firstBookEvents.next(completed());
      secondBookEvents.next(completed());

      expect(bulkUpdate).toHaveBeenCalledTimes(2);
      expect(secondBookEvents.observed).toBe(false);
      expect(bulkDialogRef.close).toHaveBeenCalledWith({completed: true});
      expect(bulkComponent.updatedBooks()).toBe(2);
    });

    it('advances without an empty metadata update when a book has no unlocked proposals', () => {
      firstBookEvents.next(completed({proposals: []}));

      expect(firstBookEvents.observed).toBe(false);
      expect(enrich).toHaveBeenLastCalledWith(8);
      expect(bulkUpdate).not.toHaveBeenCalled();
      expect(bulkComponent.unchangedBooks()).toBe(1);
    });

    it('continues after a stream error or metadata-save failure', () => {
      firstBookEvents.error(new Error('stream failed'));

      expect(enrich).toHaveBeenLastCalledWith(8);
      expect(bulkComponent.failedBooks()).toBe(1);

      bulkUpdate.mockReturnValueOnce(throwError(() => new Error('save failed')));
      secondBookEvents.next(completed());

      expect(bulkComponent.failedBooks()).toBe(2);
      expect(bulkDialogRef.close).toHaveBeenCalledWith({completed: true});
    });

    it('cancels the active queue when the dialog is closed', () => {
      bulkComponent.close();

      expect(firstBookEvents.observed).toBe(false);
      expect(enrich).toHaveBeenCalledTimes(1);
      expect(bulkDialogRef.close).toHaveBeenCalledWith(false);
    });

    it('cancels an in-flight metadata save without starting the next book', () => {
      const saveResult = new Subject<object>();
      bulkUpdate.mockReturnValueOnce(saveResult.asObservable());
      firstBookEvents.next(completed());

      expect(saveResult.observed).toBe(true);
      expect(enrich).toHaveBeenCalledTimes(1);

      bulkComponent.close();
      saveResult.next({});

      expect(saveResult.observed).toBe(false);
      expect(enrich).toHaveBeenCalledTimes(1);
      expect(bulkDialogRef.close).toHaveBeenCalledWith(false);
    });
  });
});
