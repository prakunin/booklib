import {Component, DestroyRef, effect, inject, OnDestroy, OnInit, signal} from '@angular/core';
import {RxStompService} from './shared/websocket/rx-stomp.service';
import {BookService} from './features/book/service/book.service';
import {NotificationEventService} from './shared/websocket/notification-event.service';
import {parseLogNotification} from './shared/websocket/model/log-notification.model';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Toast} from 'primeng/toast';
import {RouterOutlet} from '@angular/router';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {AuthInitializationService} from './core/security/auth-initialization-service';
import {AppThemeService} from './shared/service/app-theme.service';
import {MetadataBatchProgressNotification} from './shared/model/metadata-batch-progress.model';
import {MetadataProgressService} from './shared/service/metadata-progress.service';
import {BookdropFileNotification, BookdropFileService} from './features/bookdrop/service/bookdrop-file.service';
import {Subscription} from 'rxjs';
import {TaskProgressPayload, TaskService} from './features/settings/task-management/task.service';
import {LibraryHealthService} from './features/book/service/library-health.service';
import {AuthService} from './shared/service/auth.service';
import {CommandPaletteComponent} from './features/command-palette/command-palette.component';
import {CommandPaletteService} from './features/command-palette/command-palette.service';
import {LibraryImportProgressService} from './shared/service/library-import-progress.service';
import {AuthorService} from './features/author-browser/service/author.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  standalone: true,
  imports: [ConfirmDialog, Toast, RouterOutlet, TranslocoDirective, TranslocoPipe, CommandPaletteComponent]
})
export class AppComponent implements OnInit, OnDestroy {

  loading = signal(true);
  offline = signal(false);
  private readonly subscriptions: Subscription[] = [];
  private subscriptionsInitialized = false;

  private readonly appThemeService = inject(AppThemeService); // DO NOT REMOVE: Used to initialize app theme on startup
  private readonly authInit = inject(AuthInitializationService);
  private readonly bookService = inject(BookService);
  private readonly authorService = inject(AuthorService);
  private readonly rxStompService = inject(RxStompService);
  private readonly notificationEventService = inject(NotificationEventService);
  private readonly metadataProgressService = inject(MetadataProgressService);
  private readonly bookdropFileService = inject(BookdropFileService);
  private readonly taskService = inject(TaskService);
  private readonly libraryHealthService = inject(LibraryHealthService);
  private readonly authService = inject(AuthService);
  private readonly commandPaletteService = inject(CommandPaletteService);
  private readonly libraryImportProgressService = inject(LibraryImportProgressService);
  private readonly translocoService = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly syncAuthInitializationEffect = effect(() => {
    const ready = this.authInit.initialized();
    this.loading.set(!ready);

    if (ready && !this.subscriptionsInitialized) {
      this.setupWebSocketSubscriptions();
      this.libraryHealthService.initWebsocket();
      this.subscriptionsInitialized = true;
    }
  });

  private readonly authenticatedEffect = effect(() => {
    const authInitialized = this.authInit.initialized();
    const isAuthenticated = this.authService.isAuthenticated();
    if (authInitialized && isAuthenticated) {
      this.libraryHealthService.fetchHealth();
    }
  })

  ngOnInit(): void {
    globalThis.addEventListener('online', this.onOnline);
    globalThis.addEventListener('offline', this.onOffline);
    document.addEventListener('keydown', this.onGlobalKeydown);
    this.destroyRef.onDestroy(() => document.removeEventListener('keydown', this.onGlobalKeydown));
  }

  private readonly onGlobalKeydown = (event: KeyboardEvent): void => {
    if (event.repeat) return;
    const combo = (event.metaKey || event.ctrlKey) && !event.shiftKey && !event.altKey;
    if (!combo) return;
    if (event.key !== 'k' && event.key !== 'K') return;
    event.preventDefault();
    this.commandPaletteService.toggle();
  };

  private readonly onOnline = () => {
    this.offline.set(false);
  };

  private readonly onOffline = () => {
    this.checkServerReachable().then(reachable => {
      this.offline.set(!reachable);
    });
  };

  private checkServerReachable(): Promise<boolean> {
    return fetch('/api/public/settings', {method: 'HEAD', cache: 'no-store'})
      .then(() => true)
      .catch(() => false);
  }

  reload(): void {
    globalThis.location.reload();
  }

  private setupWebSocketSubscriptions(): void {
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-add').subscribe(msg => {
        const book = JSON.parse(msg.body);
        this.libraryImportProgressService.recordBookAdded(book.metadata?.title || this.translocoService.translate('book.unknownTitle'));
        this.bookService.handleNewlyCreatedBook(book);
        this.authorService.handleNewlyCreatedBook(book);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/library-scan-complete').subscribe(() => {
        this.bookService.handleLibraryScanComplete();
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/library-scan-progress').subscribe(msg => {
        this.libraryImportProgressService.applyScanProgress(JSON.parse(msg.body));
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-update').subscribe(msg =>
        this.bookService.handleBookUpdate(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/books-cover-update').subscribe(msg =>
        this.bookService.handleMultipleBookCoverPatches(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/books-remove').subscribe(msg =>
        this.bookService.handleRemovedBookIds(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-update').subscribe(msg =>
        this.bookService.handleBookUpdate(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-batch-update').subscribe(msg =>
        this.bookService.handleMultipleBookUpdates(JSON.parse(msg.body))
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-batch-progress').subscribe(msg =>
        this.metadataProgressService.handleIncomingProgress(JSON.parse(msg.body) as MetadataBatchProgressNotification)
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-recommendations-update').subscribe(msg =>
        this.bookService.handleBookRecommendationsUpdate(JSON.parse(msg.body) as number)
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/log').subscribe(msg => {
        const logNotification = parseLogNotification(msg.body);
        this.notificationEventService.handleNewNotification(logNotification);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/bookdrop-file').subscribe(msg => {
        const notification = JSON.parse(msg.body) as BookdropFileNotification;
        this.bookdropFileService.handleIncomingFile(notification);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/task-progress').subscribe(msg => {
        const progress = JSON.parse(msg.body) as TaskProgressPayload;
        this.taskService.handleTaskProgress(progress);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/session-revoked').subscribe(() => {
        this.authService.forceLogout('session_revoked');
      })
    );
  }

  ngOnDestroy(): void {
    globalThis.removeEventListener('online', this.onOnline);
    globalThis.removeEventListener('offline', this.onOffline);
    this.subscriptions.forEach(sub => sub.unsubscribe());
  }
}
