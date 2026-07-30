import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AuthService} from '../../../shared/service/auth.service';
import {SmartEnrichmentService} from './smart-enrichment.service';
import {SmartEnrichmentEvent} from '../model/smart-enrichment.model';

describe('SmartEnrichmentService', () => {
  let service: SmartEnrichmentService;
  let httpTestingController: HttpTestingController;
  let authService: {getInternalAccessToken: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    authService = {getInternalAccessToken: vi.fn(() => 'token-123')};

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        SmartEnrichmentService,
        {provide: AuthService, useValue: authService},
      ],
    });

    service = TestBed.inject(SmartEnrichmentService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('reports availability from the backend', async () => {
    const available = firstValue(service.available$);

    httpTestingController
      .expectOne(req => req.url.endsWith('/api/v1/books/metadata/smart-enrich/availability'))
      .flush({enabled: true});

    expect(await available).toBe(true);
  });

  // An older backend has no such endpoint; hiding the button is the right response to that, not an
  // error surfaced to the user.
  it('treats a failing availability check as unavailable', async () => {
    const available = firstValue(service.available$);

    httpTestingController
      .expectOne(req => req.url.endsWith('/api/v1/books/metadata/smart-enrich/availability'))
      .flush('nope', {status: 404, statusText: 'Not Found'});

    expect(await available).toBe(false);
  });

  it('throws when enriching without an auth token', () => {
    authService.getInternalAccessToken.mockReturnValueOnce(null);

    expect(() => service.enrich(7)).toThrowError('No authentication token available');
  });

  it('streams enrichment events over SSE', async () => {
    const event: Partial<SmartEnrichmentEvent> = {stage: 'RESOLVING', proposals: []};
    const chunk = new TextEncoder().encode(`data: ${JSON.stringify(event)}\n`);
    const mockReader = {
      read: vi.fn()
        .mockResolvedValueOnce({done: false, value: chunk})
        .mockResolvedValueOnce({done: true, value: undefined}),
      releaseLock: vi.fn(),
    };
    const fetchSpy = vi.fn().mockResolvedValue({ok: true, body: {getReader: () => mockReader}});
    vi.stubGlobal('fetch', fetchSpy);

    let received: SmartEnrichmentEvent | undefined;
    service.enrich(7).subscribe(value => (received = value));
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringMatching(/\/api\/v1\/books\/7\/metadata\/smart-enrich$/),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({Authorization: 'Bearer token-123'}),
      })
    );
    expect(received).toEqual(event);
  });

  // Runs hold an agent process open for minutes, so an unsubscribe has to reach the request.
  it('aborts the request when the caller unsubscribes', async () => {
    // A read that stays pending, standing in for a run still waiting on the agent.
    let finishRead: (() => void) | undefined;
    const mockReader = {
      read: vi.fn().mockImplementation(() => new Promise(resolve => {
        finishRead = () => resolve({done: true, value: undefined});
      })),
      releaseLock: vi.fn(),
    };
    let capturedSignal: AbortSignal | undefined;
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url, init) => {
      capturedSignal = init.signal;
      return Promise.resolve({ok: true, body: {getReader: () => mockReader}});
    }));

    const subscription = service.enrich(7).subscribe();
    await new Promise(resolve => setTimeout(resolve, 0));
    subscription.unsubscribe();

    expect(capturedSignal?.aborted).toBe(true);
    finishRead?.();
  });
});

function firstValue<T>(source: {subscribe: (observer: {next: (value: T) => void}) => unknown}): Promise<T> {
  return new Promise<T>(resolve => {
    source.subscribe({next: resolve});
  });
}
