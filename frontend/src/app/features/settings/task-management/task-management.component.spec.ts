import {NO_ERRORS_SCHEMA, provideZonelessChangeDetection} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {Observable, Subject, of, throwError} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {TranslocoService, TranslateParams} from '@jsverse/transloco';
import {MessageService} from 'primeng/api';

import {getTranslocoModule} from '../../../core/testing/transloco-testing';
import {
  MetadataReplaceMode,
  TaskHistory,
  TaskInfo,
  TaskProgressPayload,
  TaskService,
  TaskStatus,
  TaskType,
} from './task.service';
import {TaskManagementComponent} from './task-management.component';
import {UserService} from '../user-management/user.service';

describe('TaskManagementComponent', () => {
  let fixture: ComponentFixture<TaskManagementComponent>;
  let component: TaskManagementComponent;
  let taskService: {
    getAvailableTasks: ReturnType<typeof vi.fn>;
    getLatestTasksForEachType: ReturnType<typeof vi.fn>;
    getTaskOverview: ReturnType<typeof vi.fn>;
    taskProgress$: Observable<TaskProgressPayload | null>;
    startTask: ReturnType<typeof vi.fn>;
    cancelTask: ReturnType<typeof vi.fn>;
    updateCronConfig: ReturnType<typeof vi.fn>;
  };
  let taskProgressSubject: Subject<TaskProgressPayload | null>;
  let messageService: {
    add: ReturnType<typeof vi.fn>;
  };
  let translate: ReturnType<typeof vi.fn<(key: TranslateParams, params?: Record<string, unknown>, lang?: string) => unknown>>;

  function loadTaskHistory(history: TaskHistory): void {
    taskService.getLatestTasksForEachType.mockReturnValue(of({
      taskHistories: [history],
    }));
    component.loadTasks();
  }

  const clearPdfTask: TaskInfo = {
    taskType: TaskType.CLEAR_PDF_CACHE,
    name: 'Clear PDF cache',
    description: 'Clear the cached PDF files.',
    parallel: false,
    async: false,
    cronSupported: true,
    cronConfig: {
      id: 7,
      taskType: TaskType.CLEAR_PDF_CACHE,
      cronExpression: '0 0 * * * *',
      enabled: true,
      options: null,
      createdAt: '2026-03-27T03:00:00Z',
      updatedAt: '2026-03-27T03:00:00Z',
    },
    metadata: '  cache cleanup  ',
  };

  const refreshMetadataTask: TaskInfo = {
    taskType: TaskType.REFRESH_LIBRARY_METADATA,
    name: 'Refresh metadata',
    description: 'Refresh library metadata.',
    parallel: false,
    async: true,
    cronSupported: false,
    cronConfig: null,
  };

  const pendingHistory: TaskHistory = {
    id: 'task-1',
    type: TaskType.CLEAR_PDF_CACHE,
    status: TaskStatus.PENDING,
    progressPercentage: 10,
    message: 'working',
    createdAt: '2026-03-27T03:00:00Z',
    updatedAt: '2026-03-27T03:01:00Z',
    completedAt: null,
  };

  const inProgressHistory: TaskHistory = {
    ...pendingHistory,
    status: TaskStatus.IN_PROGRESS,
    updatedAt: '2026-03-27T02:55:00Z',
  };

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-03-27T03:06:00Z'));

    taskProgressSubject = new Subject<TaskProgressPayload | null>();
    translate = vi.fn<(key: TranslateParams, params?: Record<string, unknown>, lang?: string) => unknown>((key: TranslateParams, params?: Record<string, unknown>) => {
      const keyStr = typeof key === 'string' ? key : key.join(',');
      return params ? `${keyStr}:${JSON.stringify(params)}` : keyStr;
    });
    messageService = {
      add: vi.fn(),
    };
    taskService = {
      getAvailableTasks: vi.fn(() => of([refreshMetadataTask, clearPdfTask])),
      getLatestTasksForEachType: vi.fn(() => of({
        taskHistories: [pendingHistory],
      })),
      getTaskOverview: vi.fn(() => of({
        activeTasks: [{
          taskId: 'task-1',
          taskType: TaskType.CLEAR_PDF_CACHE,
          startedAt: '2026-03-27T03:00:00Z',
        }],
        scheduledTasks: [],
      })),
      taskProgress$: taskProgressSubject.asObservable(),
      startTask: vi.fn(() => of({
        type: TaskType.CLEAR_PDF_CACHE,
        status: TaskStatus.ACCEPTED,
      })),
      cancelTask: vi.fn(() => of({
        taskId: 'task-1',
        cancelled: true,
        message: 'cancelled',
      })),
      updateCronConfig: vi.fn(() => of({
        id: 7,
        taskType: TaskType.CLEAR_PDF_CACHE,
        cronExpression: '0 0 * * * *',
        enabled: true,
        options: null,
        createdAt: '2026-03-27T03:00:00Z',
        updatedAt: '2026-03-27T03:00:00Z',
      })),
    };

    TestBed.configureTestingModule({
      imports: [TaskManagementComponent, getTranslocoModule()],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideZonelessChangeDetection(),
        {provide: TaskService, useValue: taskService},
        {provide: MessageService, useValue: messageService},
        {
          provide: UserService,
          useValue: {
            currentUser: () => ({permissions: {admin: true}}),
          },
        },
      ],
    });

    const translocoService = TestBed.inject(TranslocoService);
    vi.spyOn(translocoService, 'translate').mockImplementation(translate);
    fixture = TestBed.createComponent(TaskManagementComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('loads tasks, sorts them, and attaches latest task histories on init', () => {
    component.ngOnInit();

    expect(taskService.getAvailableTasks).toHaveBeenCalledTimes(1);
    expect(taskService.getLatestTasksForEachType).toHaveBeenCalledTimes(1);
    expect(component.taskInfos.map(task => task.taskType)).toEqual([
      TaskType.REFRESH_LIBRARY_METADATA,
      TaskType.CLEAR_PDF_CACHE,
    ]);
    expect(component.getTaskHistory(TaskType.CLEAR_PDF_CACHE)).toMatchObject({
      id: 'task-1',
      status: TaskStatus.PENDING,
    });
    expect(component.loading()).toBe(false);
  });

  it('reports load failures through the message service', () => {
    taskService.getAvailableTasks = vi.fn(() => throwError(() => new Error('boom')));

    component.ngOnInit();

    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'common.error',
      detail: 'settingsTasks.toast.loadError',
    });
  });

  it('tracks task progress updates and reloads after a completed task', () => {
    const loadTasksSpy = vi.spyOn(component, 'loadTasks');

    fixture.detectChanges();
    expect(loadTasksSpy).toHaveBeenCalledTimes(1);

    taskProgressSubject.next({
      taskId: 'task-2',
      taskType: TaskType.CLEAR_PDF_CACHE,
      message: 'done',
      progress: 100,
      taskStatus: TaskStatus.COMPLETED,
    });

    expect(component.getTaskHistory(TaskType.CLEAR_PDF_CACHE)).toMatchObject({
      id: 'task-2',
      status: TaskStatus.COMPLETED,
      completedAt: expect.any(String),
    });

    vi.advanceTimersByTime(1000);

    expect(loadTasksSpy).toHaveBeenCalledTimes(2);
  });

  it('refreshes task progress in the DOM after websocket updates', async () => {
    vi.useRealTimers();

    fixture.detectChanges();
    await fixture.whenStable();

    taskProgressSubject.next({
      taskId: 'task-2',
      taskType: TaskType.CLEAR_PDF_CACHE,
      message: 'halfway there',
      progress: 45,
      taskStatus: TaskStatus.IN_PROGRESS,
    });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('halfway there');
    expect(text).toContain('45%');
  });

  it('shows persisted progress in the administrator overview after page load', async () => {
    vi.useRealTimers();
    taskService.getLatestTasksForEachType.mockReturnValue(of({
      taskHistories: [{
        ...pendingHistory,
        message: 'Generated semantic embeddings: 44416/701579 books',
      }],
    }));

    fixture.detectChanges();
    await fixture.whenStable();

    const overview = (fixture.nativeElement as HTMLElement).querySelector('.overview-card');
    expect(overview?.textContent).toContain('Generated semantic embeddings: 44416/701579 books');
    expect(overview?.textContent).toContain('10%');
    expect(overview?.textContent).toContain('settingsTasks.overview.processed:{"processed":"44,416","total":"701,579"}');
  });

  it('refreshes both task overview and persisted progress when requested', () => {
    component.ngOnInit();
    taskService.getLatestTasksForEachType.mockReturnValue(of({
      taskHistories: [{
        ...pendingHistory,
        progressPercentage: 55,
        message: 'Generated semantic embeddings: 385868/701579 books',
      }],
    }));

    component.refreshTaskOverview();

    expect(taskService.getTaskOverview).toHaveBeenCalledTimes(2);
    expect(taskService.getLatestTasksForEachType).toHaveBeenCalledTimes(2);
    expect(component.getActiveTaskProgress(component.taskOverview().activeTasks[0])).toBe(55);
    expect(component.getActiveTaskProcessedCounts(component.taskOverview().activeTasks[0])).toEqual({
      processed: '385,868',
      total: '701,579',
    });
    expect(component.overviewLoading()).toBe(false);
  });

  it('polls persisted progress while an administrator task is active', () => {
    component.ngOnInit();
    const overviewCallsBeforePolling = taskService.getTaskOverview.mock.calls.length;
    const latestCallsBeforePolling = taskService.getLatestTasksForEachType.mock.calls.length;
    taskService.getLatestTasksForEachType.mockReturnValue(of({
      taskHistories: [{
        ...pendingHistory,
        progressPercentage: 61,
        message: 'Generated semantic embeddings: 427984/701579 books',
      }],
    }));

    vi.advanceTimersByTime(5000);

    expect(taskService.getTaskOverview.mock.calls.length).toBeGreaterThan(overviewCallsBeforePolling);
    expect(taskService.getLatestTasksForEachType.mock.calls.length).toBeGreaterThan(latestCallsBeforePolling);
    expect(component.getActiveTaskProgress(component.taskOverview().activeTasks[0])).toBe(61);
    expect(component.getActiveTaskMessage(component.taskOverview().activeTasks[0]))
      .toBe('Generated semantic embeddings: 427984/701579 books');
  });

  it('distinguishes running, stale, and cancellable tasks', () => {
    loadTaskHistory(inProgressHistory);

    expect(component.isTaskRunning(TaskType.CLEAR_PDF_CACHE)).toBe(true);
    expect(component.canCancelTask(inProgressHistory)).toBe(true);
    expect(component.canExecuteTask(TaskType.CLEAR_PDF_CACHE)).toBe(true);
    expect(component.isTaskStale(inProgressHistory)).toBe(true);
    expect(component.getTaskButtonLabel(TaskType.CLEAR_PDF_CACHE)).toBe('settingsTasks.buttons.rerun');

    loadTaskHistory({
      ...inProgressHistory,
      updatedAt: '2026-03-27T03:05:45Z',
    });

    expect(component.isTaskStale(component.getTaskHistory(TaskType.CLEAR_PDF_CACHE))).toBe(false);
    expect(component.canExecuteTask(TaskType.CLEAR_PDF_CACHE)).toBe(false);
    expect(component.getTaskButtonLabel(TaskType.CLEAR_PDF_CACHE)).toBe('settingsTasks.buttons.run');
  });

  it('blocks duplicate task execution when the task is still fresh', () => {
    loadTaskHistory({
      ...inProgressHistory,
      updatedAt: '2026-03-27T03:05:45Z',
    });

    component.runTask(TaskType.CLEAR_PDF_CACHE);

    expect(taskService.startTask).not.toHaveBeenCalled();
    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'warn',
      summary: 'settingsTasks.toast.alreadyRunning',
      detail: 'settingsTasks.toast.alreadyRunningDetail',
    });
  });

  it('queues async metadata refresh tasks with the selected replace mode', () => {
    component.selectedMetadataReplaceMode = MetadataReplaceMode.REPLACE_ALL;

    component.runTask(TaskType.REFRESH_LIBRARY_METADATA);

    expect(taskService.startTask).toHaveBeenCalledWith({
      taskType: TaskType.REFRESH_LIBRARY_METADATA,
      triggeredByCron: false,
      options: {
        metadataReplaceMode: MetadataReplaceMode.REPLACE_ALL,
      },
    });
    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'info',
      summary: 'settingsTasks.toast.taskQueued',
      detail: 'settingsTasks.toast.taskQueuedDetail:{"name":"Refresh Library Metadata"}',
    });
  });

  it('reports sync task completion and failure outcomes', () => {
    loadTaskHistory({
      ...pendingHistory,
      status: TaskStatus.COMPLETED,
      completedAt: '2026-03-27T03:05:50Z',
      updatedAt: '2026-03-27T03:05:50Z',
    });
    taskService.startTask = vi.fn(() => of({
      type: TaskType.CLEAR_PDF_CACHE,
      status: TaskStatus.COMPLETED,
    }));
    component.runTask(TaskType.CLEAR_PDF_CACHE);

    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'success',
      summary: 'settingsTasks.toast.taskCompleted',
      detail: 'settingsTasks.toast.taskCompletedDetail:{"name":"Clear PDF cache"}',
    });

    messageService.add.mockClear();
    loadTaskHistory({
      ...pendingHistory,
      status: TaskStatus.COMPLETED,
      completedAt: '2026-03-27T03:05:55Z',
      updatedAt: '2026-03-27T03:05:55Z',
    });
    taskService.startTask = vi.fn(() => of({
      type: TaskType.CLEAR_PDF_CACHE,
      status: TaskStatus.FAILED,
      message: 'nope',
    }));
    component.runTask(TaskType.CLEAR_PDF_CACHE);

    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'settingsTasks.toast.taskFailed',
      detail: 'nope',
    });
  });

  it('cancels tasks and handles both success and failure paths', () => {
    loadTaskHistory(pendingHistory);
    component.cancelTask(TaskType.CLEAR_PDF_CACHE);

    expect(taskService.cancelTask).toHaveBeenCalledWith('task-1');
    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'success',
      summary: 'settingsTasks.toast.taskCancelled',
      detail: 'cancelled',
    });

    messageService.add.mockClear();
    taskService.cancelTask = vi.fn(() => of({
      taskId: 'task-1',
      cancelled: false,
      message: 'busy',
    }));
    component.cancelTask(TaskType.CLEAR_PDF_CACHE);

    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'settingsTasks.toast.cancelFailed',
      detail: 'busy',
    });
  });

  it('validates cron expressions and saves trimmed updates', () => {
    component.taskInfos = [clearPdfTask];

    component.startEditingCron(TaskType.CLEAR_PDF_CACHE);
    expect(component.isEditingCron(TaskType.CLEAR_PDF_CACHE)).toBe(true);
    expect(component.editingCronExpression).toBe('0 0 * * * *');

    component.editingCronExpression = '*/5 0-23 * * ? 0';
    component.onCronExpressionChange();
    expect(component.cronValidationError).toBeNull();

    component.editingCronExpression = 'invalid';
    component.onCronExpressionChange();
    expect(component.cronValidationError).toBe('settingsTasks.cron.validationPrefix');

    component.saveCronExpression(TaskType.CLEAR_PDF_CACHE);
    expect(taskService.updateCronConfig).not.toHaveBeenCalled();

    component.editingCronExpression = ' 0 15 * * * * ';
    component.onCronExpressionChange();
    component.saveCronExpression(TaskType.CLEAR_PDF_CACHE);

    expect(taskService.updateCronConfig).toHaveBeenCalledWith(TaskType.CLEAR_PDF_CACHE, {
      cronExpression: '0 15 * * * *',
    });
    expect(component.isEditingCron(TaskType.CLEAR_PDF_CACHE)).toBe(false);
    expect(component.cronValidationError).toBeNull();
  });

  it('updates cron state and exposes helper text and metadata helpers', () => {
    component.taskInfos = [clearPdfTask];
    loadTaskHistory({
      ...pendingHistory,
      status: TaskStatus.COMPLETED,
      completedAt: '2026-03-27T03:05:50Z',
      updatedAt: '2026-03-27T03:05:50Z',
    });

    component.toggleCronEnabled(TaskType.CLEAR_PDF_CACHE);
    expect(taskService.updateCronConfig).toHaveBeenCalledWith(TaskType.CLEAR_PDF_CACHE, {
      enabled: false,
    });
    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'success',
      summary: 'common.success',
      detail: 'settingsTasks.cron.updateSuccess',
    });

    expect(component.getCronConfig(TaskType.CLEAR_PDF_CACHE)).toEqual({
      enabled: true,
      cronExpression: '0 0 * * * *',
    });
    expect(component.getMetadataReplaceDescription(MetadataReplaceMode.REPLACE_ALL)).toBe('settingsTasks.metadataReplace.replaceAllDesc');
    expect(component.getMetadataReplaceDescription(MetadataReplaceMode.REPLACE_MISSING)).toBe('settingsTasks.metadataReplace.replaceMissingDesc');
    expect(component.getTaskDisplayName(TaskType.CLEAR_PDF_CACHE)).toBe('Clear PDF cache');
    expect(component.getTaskDescription(TaskType.CLEAR_PDF_CACHE)).toBe('Clear the cached PDF files.');
    expect(component.getTaskLabel(TaskType.CLEAR_PDF_CACHE)).toBe('8. Clear PDF cache');
    expect(component.getTaskIcon(TaskType.CLEAR_PDF_CACHE)).toBe('pi-database');
    expect(component.getMetadataIcon(TaskType.CLEAR_PDF_CACHE)).toBe('pi-database');
    expect(component.hasMetadata(TaskType.CLEAR_PDF_CACHE)).toBe(true);
    expect(component.getLastRunMessage(TaskType.CLEAR_PDF_CACHE)).toBe('settingsTasks.status.justNow');
    expect(component.getLastRunInfoClass(TaskType.CLEAR_PDF_CACHE)).toBe('success');
    expect(component.getTaskButtonIcon(TaskType.CLEAR_PDF_CACHE)).toBe('pi pi-database');
    expect(component.getCancelButtonIcon(TaskType.CLEAR_PDF_CACHE)).toBe('pi pi-times');
    expect(component.formatDate('2026-03-27T03:00:00Z')).toContain('2026');
  });
});
