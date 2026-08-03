import {TestBed} from '@angular/core/testing';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {of, throwError} from 'rxjs';
import {describe, expect, it, vi} from 'vitest';

import {EnrichmentComponent} from './enrichment.component';
import {EnrichmentService} from '../../service/enrichment.service';
import {SmartEnrichmentService} from '../../service/smart-enrichment.service';
import {getTranslocoModule} from '../../../../core/testing/transloco-testing';

describe('EnrichmentComponent', () => {
  let enrichmentService: {enrich: ReturnType<typeof vi.fn>};
  let dialogRef: {close: ReturnType<typeof vi.fn>};

  function createComponent(data: Record<string, unknown>, agentAvailable = true): EnrichmentComponent {
    enrichmentService = {enrich: vi.fn(() => of({jobId: 'job-1'}))};
    dialogRef = {close: vi.fn()};

    TestBed.configureTestingModule({
      imports: [EnrichmentComponent, getTranslocoModule()],
      providers: [
        {provide: DynamicDialogConfig, useValue: {data}},
        {provide: DynamicDialogRef, useValue: dialogRef},
        {provide: EnrichmentService, useValue: enrichmentService},
        {provide: SmartEnrichmentService, useValue: {available$: of(agentAvailable)}},
        MessageService,
      ],
    });
    return TestBed.createComponent(EnrichmentComponent).componentInstance;
  }

  it('selects every offered source by default', () => {
    const component = createComponent({scope: 'BOOKS', bookIds: [1, 2]});

    expect(component.selectedSteps()).toEqual([...component.selectableSteps]);
    expect(component.canSubmit()).toBe(true);
  });

  it('cannot submit with no source selected', () => {
    const component = createComponent({scope: 'BOOK', bookIds: [1]});

    component.selectableSteps.forEach((step) => component.toggleStep(step, false));

    expect(component.canSubmit()).toBe(false);
  });

  it('defaults to filling empty fields only', () => {
    const component = createComponent({scope: 'BOOK', bookIds: [1]});

    expect(component.writePolicy()).toBe('AUTO_IF_EMPTY');
  });

  it('queues the selected books and closes with the job id', () => {
    const component = createComponent({scope: 'BOOKS', bookIds: [1, 2]});

    component.submit();

    expect(enrichmentService.enrich).toHaveBeenCalledWith(
      expect.objectContaining({scope: 'BOOKS', bookIds: [1, 2], writePolicy: 'AUTO_IF_EMPTY'})
    );
    expect(dialogRef.close).toHaveBeenCalledWith({jobId: 'job-1'});
  });

  it('sends no book ids for a library sweep, because the backend resolves the set', () => {
    const component = createComponent({scope: 'LIBRARY', libraryId: 4, libraryBookCount: 200000});

    component.submit();

    expect(enrichmentService.enrich).toHaveBeenCalledWith(
      expect.objectContaining({scope: 'LIBRARY', libraryId: 4, bookIds: undefined})
    );
  });

  /**
   * The agent binary is operator-supplied. Asking for it on an instance without it could only fail,
   * so the request must not carry the flag.
   */
  it('never asks for the agent when the instance has none', () => {
    const component = createComponent({scope: 'BOOK', bookIds: [1]}, false);
    component.agentAllowed.set(true);

    component.submit();

    expect(enrichmentService.enrich).toHaveBeenCalledWith(expect.objectContaining({agentAllowed: false}));
  });

  it('warns before letting the agent loose on a large sweep', () => {
    const component = createComponent({scope: 'LIBRARY', libraryId: 4, libraryBookCount: 200000});

    expect(component.agentWarning()).toBe(false);
    component.agentAllowed.set(true);
    expect(component.agentWarning()).toBe(true);
  });

  it('keeps the dialog open when queueing fails', () => {
    const component = createComponent({scope: 'BOOK', bookIds: [1]});
    enrichmentService.enrich.mockReturnValue(throwError(() => new Error('rejected')));

    component.submit();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.canSubmit()).toBe(true);
  });
});
