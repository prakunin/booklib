import {TestBed} from '@angular/core/testing';
import {ConfirmationService, MessageService} from 'primeng/api';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {LoadingService} from '../../../core/services/loading.service';
import {TranslocoService} from '@jsverse/transloco';
import {BookMetadataManageService} from './book-metadata-manage.service';
import {BookMenuService} from './book-menu.service';
import {BookService} from './book.service';
import {User} from '../../settings/user-management/user.service';

describe('BookMenuService metadata actions', () => {
  let service: BookMenuService;

  const user = (canBulkEditMetadata: boolean): User => ({
    permissions: {canBulkEditMetadata},
  } as User);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        BookMenuService,
        {provide: ConfirmationService, useValue: {}},
        {provide: MessageService, useValue: {}},
        {provide: BookService, useValue: {}},
        {provide: BookMetadataManageService, useValue: {}},
        {provide: LoadingService, useValue: {}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ],
    });
    service = TestBed.inject(BookMenuService);
  });

  it('shows Smart Enrich only when it is available and bulk metadata editing is permitted', () => {
    const command = vi.fn();
    const items = service.getMetadataMenuItems(
      vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn(),
      user(true),
      command,
      true
    );

    const smartEnrich = items.find(item => item.label === 'book.menuService.menu.smartEnrich');
    expect(smartEnrich).toBeDefined();

    smartEnrich?.command?.({} as never);
    expect(command).toHaveBeenCalledTimes(1);
  });

  it('hides Smart Enrich when the feature is unavailable or permission is absent', () => {
    const buildItems = (available: boolean, permitted: boolean) => service.getMetadataMenuItems(
      vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn(),
      user(permitted),
      vi.fn(),
      available
    );

    expect(buildItems(false, true).some(item => item.label === 'book.menuService.menu.smartEnrich')).toBe(false);
    expect(buildItems(true, false).some(item => item.label === 'book.menuService.menu.smartEnrich')).toBe(false);
  });
});

// NOTE(frontend-seam): Real coverage here needs seams around confirmation-dialog callbacks,
// loader orchestration, and message-service side effects so menu-action branching can be asserted
// without reproducing the full imperative overlay runtime.
describe.skip('BookMenuService', () => {
  it('needs dialog seams to verify delete, merge, send, rescan, and metadata-refresh action branching', () => {
    // TODO(seam): Cover menu item creation and destructive-action callbacks once confirm dialog payloads can be asserted directly.
  });

  it('needs loader and toast seams to verify success, failure, and bulk-operation messaging paths', () => {
    // TODO(seam): Cover async action handlers after loader wrappers and message dispatch are extracted behind deterministic collaborators.
  });
});
