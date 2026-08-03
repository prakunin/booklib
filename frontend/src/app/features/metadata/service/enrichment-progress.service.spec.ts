import {TestBed} from '@angular/core/testing';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {EnrichmentProgressService} from './enrichment-progress.service';
import {EnrichmentProgressEvent} from '../model/enrichment.model';

describe('EnrichmentProgressService', () => {
  let service: EnrichmentProgressService;
  let queryClient: QueryClient;
  let invalidateQueries: ReturnType<typeof vi.fn>;

  const event = (overrides: Partial<EnrichmentProgressEvent> = {}): EnrichmentProgressEvent => ({
    jobId: 'job-1',
    bookId: 7,
    total: 10,
    completed: 3,
    outstanding: 7,
    finished: false,
    bookChanged: true,
    notes: [],
    ...overrides,
  });

  beforeEach(() => {
    invalidateQueries = vi.fn();
    queryClient = {invalidateQueries} as unknown as QueryClient;
    TestBed.configureTestingModule({
      providers: [{provide: QueryClient, useValue: queryClient}],
    });
    service = TestBed.inject(EnrichmentProgressService);
  });

  it('exposes the latest progress', () => {
    service.handleProgress(event());

    expect(service.progress()?.jobId).toBe('job-1');
    expect(service.isRunning()).toBe(true);
  });

  it('stops reporting a run once it finishes', () => {
    service.handleProgress(event({finished: true, outstanding: 0}));

    expect(service.isRunning()).toBe(false);
  });

  /**
   * The query client uses staleTime: Infinity, so a book enriched in the background keeps rendering
   * its old metadata until something invalidates it.
   */
  it('invalidates the enriched book so the UI shows the change', () => {
    service.handleProgress(event({bookChanged: true}));

    expect(invalidateQueries).toHaveBeenCalled();
  });

  it('does not invalidate anything when the book was left unchanged', () => {
    service.handleProgress(event({bookChanged: false}));

    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('clears the reported run', () => {
    service.handleProgress(event());
    service.clear();

    expect(service.progress()).toBeNull();
    expect(service.isRunning()).toBe(false);
  });
});
