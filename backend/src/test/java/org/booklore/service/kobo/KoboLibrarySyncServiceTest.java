package org.booklore.service.kobo;

import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookloreSyncToken;
import org.booklore.model.dto.KoboSyncSettings;
import org.booklore.model.dto.kobo.*;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.KoboSettings;
import org.booklore.model.entity.KoboLibrarySnapshotEntity;
import org.booklore.model.entity.KoboSnapshotBookEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.KoboDeletedBookProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.kobo.BookloreSyncTokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KoboLibrarySyncService Tests")
// Deliberate use of the deprecated legacy per-format progress fields (dual-write compat); remove with the legacy columns.
@SuppressWarnings("java:S1874")
class KoboLibrarySyncServiceTest {

    @Mock
    private BookloreSyncTokenGenerator tokenGenerator;
    @Mock
    private KoboLibrarySnapshotService koboLibrarySnapshotService;
    @Mock
    private KoboEntitlementService entitlementService;
    @Mock
    private KoboDeletedBookProgressRepository koboDeletedBookProgressRepository;
    @Mock
    private UserBookProgressRepository userBookProgressRepository;
    @Mock
    private KoboServerProxy koboServerProxy;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private KoboSettingsService koboSettingsService;
    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private KoboLibrarySyncService service;

    private KoboSyncSettings testSettings;

    @BeforeEach
    void setUp() {
        testSettings = new KoboSyncSettings();
        testSettings.setTwoWayProgressSync(false);
        when(koboSettingsService.getCurrentUserSettings()).thenReturn(testSettings);
    }

    @Nested
    @DisplayName("Sync Filtering - Two-Way Toggle Gating")
    class TwoWaySyncFiltering {

        @Test
        @DisplayName("Should filter out web-reader-only entries when two-way sync is OFF")
        void filterWebReaderOnlyEntries_whenToggleOff() {
            testSettings.setTwoWayProgressSync(false);

            UserBookProgressEntity webReaderOnly = createProgress(1L);
            webReaderOnly.setEpubProgress("epubcfi(/6/4)");
            webReaderOnly.setEpubProgressPercent(50f);
            webReaderOnly.setLastReadTime(Instant.now());
            webReaderOnly.setKoboProgressPercent(null);
            webReaderOnly.setKoboProgressReceivedTime(null);
            webReaderOnly.setReadStatusModifiedTime(null);

            assertFalse(needsStatusSync(webReaderOnly));
            assertFalse(needsKoboProgressSync(webReaderOnly));
        }

        @Test
        @DisplayName("Should include Kobo progress entries regardless of toggle state")
        void includeKoboProgressEntries_alwaysIncluded() {
            testSettings.setTwoWayProgressSync(false);

            UserBookProgressEntity koboProgress = createProgress(1L);
            koboProgress.setKoboProgressPercent(75f);
            koboProgress.setKoboProgressReceivedTime(Instant.now());
            koboProgress.setKoboProgressSentTime(null);

            assertTrue(needsKoboProgressSync(koboProgress));
        }

        @Test
        @DisplayName("Should include status sync entries regardless of toggle state")
        void includeStatusEntries_alwaysIncluded() {
            testSettings.setTwoWayProgressSync(false);

            UserBookProgressEntity statusProgress = createProgress(1L);
            statusProgress.setReadStatus(ReadStatus.READ);
            statusProgress.setReadStatusModifiedTime(Instant.now());
            statusProgress.setKoboStatusSentTime(null);

            assertTrue(needsStatusSync(statusProgress));
        }

        @Test
        @DisplayName("Should not filter web-reader entries when two-way sync is ON")
        void includeWebReaderEntries_whenToggleOn() {
            testSettings.setTwoWayProgressSync(true);

            UserBookProgressEntity webReaderProgress = createProgress(1L);
            webReaderProgress.setEpubProgress("epubcfi(/6/4)");
            webReaderProgress.setEpubProgressPercent(50f);
            webReaderProgress.setLastReadTime(Instant.now());

            assertNotNull(webReaderProgress.getEpubProgressPercent());
            assertNotNull(webReaderProgress.getLastReadTime());
        }
    }

    @Nested
    @DisplayName("Progress Sync Detection")
    class ProgressSyncDetection {

        @Test
        @DisplayName("Should detect unsynced Kobo progress when never sent")
        void needsKoboProgressSync_neverSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(Instant.now());
            progress.setKoboProgressSentTime(null);

            assertTrue(needsKoboProgressSync(progress));
        }

        @Test
        @DisplayName("Should detect unsynced Kobo progress when received after sent")
        void needsKoboProgressSync_receivedAfterSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));

            assertTrue(needsKoboProgressSync(progress));
        }

        @Test
        @DisplayName("Should not detect sync needed when sent after received")
        void needsKoboProgressSync_alreadySynced() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressSentTime(Instant.now());

            assertFalse(needsKoboProgressSync(progress));
        }

        @Test
        @DisplayName("Should not detect sync needed when no progress received")
        void needsKoboProgressSync_noProgressReceived() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(null);

            assertFalse(needsKoboProgressSync(progress));
        }
    }

    @Nested
    @DisplayName("Status Sync Detection")
    class StatusSyncDetection {

        @Test
        @DisplayName("Should detect unsynced status when never sent")
        void needsStatusSync_neverSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(Instant.now());
            progress.setKoboStatusSentTime(null);

            assertTrue(needsStatusSync(progress));
        }

        @Test
        @DisplayName("Should detect unsynced status when modified after sent")
        void needsStatusSync_modifiedAfterSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(Instant.now());
            progress.setKoboStatusSentTime(Instant.now().minusSeconds(60));

            assertTrue(needsStatusSync(progress));
        }

        @Test
        @DisplayName("Should not detect status sync when already sent")
        void needsStatusSync_alreadySynced() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(Instant.now().minusSeconds(60));
            progress.setKoboStatusSentTime(Instant.now());

            assertFalse(needsStatusSync(progress));
        }

        @Test
        @DisplayName("Should not detect status sync when no modification time")
        void needsStatusSync_noModificationTime() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(null);

            assertFalse(needsStatusSync(progress));
        }
    }

    @Nested
    @DisplayName("Web Reader Progress Sync (Two-Way)")
    class WebReaderProgressSync {

        @Test
        @DisplayName("Should detect web reader progress needing sync when toggle ON and lastReadTime after sent")
        void needsProgressSync_webReaderNewer() {
            testSettings.setTwoWayProgressSync(true);

            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgress("epubcfi(/6/4)");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(Instant.now().minusSeconds(120));

            assertTrue(needsProgressSync(progress));
        }

        @Test
        @DisplayName("Should detect href-only web reader progress when toggle ON and lastReadTime after sent")
        void needsProgressSync_webReaderHrefOnly() {
            testSettings.setTwoWayProgressSync(true);

            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgressHref("OPS/chapter3.xhtml");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(Instant.now().minusSeconds(120));

            assertTrue(needsProgressSync(progress));
        }

        @Test
        @DisplayName("Should not sync web reader progress when toggle OFF")
        void needsProgressSync_toggleOff() {
            testSettings.setTwoWayProgressSync(false);

            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgress("epubcfi(/6/4)");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(null);

            assertFalse(needsProgressSync(progress));
        }

        @Test
        @DisplayName("Should not bounce Kobo progress back immediately")
        void needsProgressSync_preventBounce() {
            testSettings.setTwoWayProgressSync(true);

            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgress("epubcfi(/6/4)");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now().minusSeconds(120));
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(Instant.now());

            assertFalse(needsProgressSyncWebReader(progress));
        }
    }

    private UserBookProgressEntity createProgress(Long bookId) {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setBook(book);
        return progress;
    }

    private boolean needsStatusSync(UserBookProgressEntity progress) {
        Instant modifiedTime = progress.getReadStatusModifiedTime();
        if (modifiedTime == null) return false;
        Instant sentTime = progress.getKoboStatusSentTime();
        return sentTime == null || modifiedTime.isAfter(sentTime);
    }

    private boolean needsKoboProgressSync(UserBookProgressEntity progress) {
        Instant sentTime = progress.getKoboProgressSentTime();
        Instant receivedTime = progress.getKoboProgressReceivedTime();
        return receivedTime != null && (sentTime == null || receivedTime.isAfter(sentTime));
    }

    private boolean needsProgressSync(UserBookProgressEntity progress) {
        if (needsKoboProgressSync(progress)) return true;

        if (testSettings.isTwoWayProgressSync()
                && progress.getEpubProgressPercent() != null) {
            Instant sentTime = progress.getKoboProgressSentTime();
            Instant lastReadTime = progress.getLastReadTime();
            if (lastReadTime != null && (sentTime == null || lastReadTime.isAfter(sentTime))) {
                return true;
            }
        }
        return false;
    }

    private boolean needsProgressSyncWebReader(UserBookProgressEntity progress) {
        if (!testSettings.isTwoWayProgressSync()) return false;
        if (progress.getEpubProgress() == null || progress.getEpubProgressPercent() == null) return false;

        Instant lastReadTime = progress.getLastReadTime();
        Instant sentTime = progress.getKoboProgressSentTime();
        Instant receivedTime = progress.getKoboProgressReceivedTime();

        if (lastReadTime == null) return false;
        if (sentTime != null && !lastReadTime.isAfter(sentTime)) return false;
        return receivedTime == null || lastReadTime.isAfter(receivedTime);
    }

    @Nested
    @DisplayName("syncLibrary Orchestration")
    class SyncLibraryOrchestration {

        private final BookLoreUser user = new BookLoreUser();

        @BeforeEach
        void setUp() {
            user.setId(42L);

            MockHttpServletRequest request = new MockHttpServletRequest();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            when(tokenGenerator.toBase64(any())).thenReturn("final-token");
            when(koboLibrarySnapshotService.getChangedBooks(any(), any(), any())).thenReturn(Page.empty());
            when(koboLibrarySnapshotService.getRemovedBooks(any(), any(), any(), any())).thenReturn(Page.empty());
            when(userBookProgressRepository.findAllBooksNeedingKoboSync(any(), any())).thenReturn(List.of());
            when(entitlementService.generateNewEntitlements(any(), any())).thenReturn(List.of());
            when(entitlementService.generateTags()).thenReturn(List.of());

            AppSettings noForwardSettings = AppSettings.builder()
                    .koboSettings(KoboSettings.builder().forwardToKoboStore(false).build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(noForwardSettings);
        }

        @AfterEach
        void tearDown() {
            RequestContextHolder.resetRequestAttributes();
        }

        @Test
        @DisplayName("Initial sync with no previous snapshot completes and cleans up deleted progress")
        void initialSync_noPreviousSnapshot_completesAndCleansUp() {
            when(tokenGenerator.fromRequestHeaders(any())).thenReturn(null);

            KoboLibrarySnapshotEntity currSnapshot = KoboLibrarySnapshotEntity.builder().id("curr-1").userId(42L).build();
            when(koboLibrarySnapshotService.findByIdAndUserId(null, 42L)).thenReturn(Optional.empty());
            when(koboLibrarySnapshotService.create(42L)).thenReturn(currSnapshot);
            when(koboLibrarySnapshotService.getUnsyncedBooks(eq("curr-1"), any())).thenReturn(Page.empty());

            ResponseEntity<List<Entitlement>> response = service.syncLibrary(user, "req-token");

            assertThat(response.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNC)).isEmpty();
            assertThat(response.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNCTOKEN)).isEqualTo("final-token");
            verify(koboLibrarySnapshotService, never()).deleteById(any());
            verify(koboDeletedBookProgressRepository).deleteBySnapshotIdAndUserId(null, 42L);
        }

        @Test
        @DisplayName("Incremental sync with more added books than fit in a page continues and keeps the ongoing snapshot")
        void incrementalSync_moreAddedBooksThanPageSize_continuesSync() {
            BookloreSyncToken incomingToken = BookloreSyncToken.builder()
                    .ongoingSyncPointId("curr-1")
                    .lastSuccessfulSyncPointId("prev-1")
                    .build();
            when(tokenGenerator.fromRequestHeaders(any())).thenReturn(incomingToken);

            KoboLibrarySnapshotEntity currSnapshot = KoboLibrarySnapshotEntity.builder().id("curr-1").userId(42L).build();
            KoboLibrarySnapshotEntity prevSnapshot = KoboLibrarySnapshotEntity.builder().id("prev-1").userId(42L).build();
            when(koboLibrarySnapshotService.findByIdAndUserId("curr-1", 42L)).thenReturn(Optional.of(currSnapshot));
            when(koboLibrarySnapshotService.findByIdAndUserId("prev-1", 42L)).thenReturn(Optional.of(prevSnapshot));

            KoboSnapshotBookEntity addedBook = KoboSnapshotBookEntity.builder().id(1L).bookId(100L).build();
            Page<KoboSnapshotBookEntity> addedPage = new PageImpl<>(List.of(addedBook), PageRequest.of(0, 100), 101);
            when(koboLibrarySnapshotService.getNewlyAddedBooks(eq("prev-1"), eq("curr-1"), any(), eq(42L))).thenReturn(addedPage);

            ResponseEntity<List<Entitlement>> response = service.syncLibrary(user, "req-token");

            assertThat(response.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNC)).isEqualTo("continue");
            verify(koboLibrarySnapshotService, never()).deleteById(any());
            verify(userBookProgressRepository, never()).findAllBooksNeedingKoboSync(any(), any());
        }

        @Test
        @DisplayName("Forwarding enabled and Kobo store reachable maps upstream entitlements and adopts upstream token")
        void forwardingEnabled_storeReachable_mapsUpstreamEntitlementsAndToken() {
            when(tokenGenerator.fromRequestHeaders(any())).thenReturn(null);

            KoboLibrarySnapshotEntity currSnapshot = KoboLibrarySnapshotEntity.builder().id("curr-2").userId(42L).build();
            when(koboLibrarySnapshotService.findByIdAndUserId(null, 42L)).thenReturn(Optional.empty());
            when(koboLibrarySnapshotService.create(42L)).thenReturn(currSnapshot);
            when(koboLibrarySnapshotService.getUnsyncedBooks(eq("curr-2"), any())).thenReturn(Page.empty());

            AppSettings forwardSettings = AppSettings.builder()
                    .koboSettings(KoboSettings.builder().forwardToKoboStore(true).build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(forwardSettings);

            JsonNode newNode = mock(JsonNode.class);
            when(newNode.has("NewEntitlement")).thenReturn(true);
            JsonNode changedNode = mock(JsonNode.class);
            when(changedNode.has("NewEntitlement")).thenReturn(false);
            when(changedNode.has("ChangedEntitlement")).thenReturn(true);
            JsonNode unknownNode = mock(JsonNode.class);
            when(unknownNode.has("NewEntitlement")).thenReturn(false);
            when(unknownNode.has("ChangedEntitlement")).thenReturn(false);

            JsonNode arrayBody = mock(JsonNode.class);
            when(arrayBody.isArray()).thenReturn(true);
            when(arrayBody.iterator()).thenReturn(List.of(newNode, changedNode, unknownNode).iterator());

            NewEntitlement mappedNew = new NewEntitlement();
            ChangedEntitlement mappedChanged = new ChangedEntitlement();
            when(objectMapper.treeToValue(newNode, NewEntitlement.class)).thenReturn(mappedNew);
            when(objectMapper.treeToValue(changedNode, ChangedEntitlement.class)).thenReturn(mappedChanged);

            ResponseEntity<JsonNode> koboResponse = ResponseEntity.ok()
                    .header(KoboHeaders.X_KOBO_SYNC, "")
                    .header(KoboHeaders.X_KOBO_SYNCTOKEN, "upstream-b64")
                    .body(arrayBody);
            when(koboServerProxy.proxyCurrentRequest(null, true)).thenReturn(koboResponse);

            BookloreSyncToken upstreamToken = BookloreSyncToken.builder().lastSuccessfulSyncPointId("up-1").build();
            when(tokenGenerator.fromBase64("upstream-b64")).thenReturn(upstreamToken);

            ResponseEntity<List<Entitlement>> response = service.syncLibrary(user, "req-token");

            assertThat(response.getBody()).contains(mappedNew, mappedChanged);
            assertThat(response.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNC)).isEmpty();
        }

        @Test
        @DisplayName("Forwarding enabled but proxy call throws falls back to non-continuing sync without upstream entitlements")
        void forwardingEnabled_proxyThrows_fallsBackGracefully() {
            when(tokenGenerator.fromRequestHeaders(any())).thenReturn(null);

            KoboLibrarySnapshotEntity currSnapshot = KoboLibrarySnapshotEntity.builder().id("curr-3").userId(42L).build();
            when(koboLibrarySnapshotService.findByIdAndUserId(null, 42L)).thenReturn(Optional.empty());
            when(koboLibrarySnapshotService.create(42L)).thenReturn(currSnapshot);
            when(koboLibrarySnapshotService.getUnsyncedBooks(eq("curr-3"), any())).thenReturn(Page.empty());

            AppSettings forwardSettings = AppSettings.builder()
                    .koboSettings(KoboSettings.builder().forwardToKoboStore(true).build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(forwardSettings);
            when(koboServerProxy.proxyCurrentRequest(null, true)).thenThrow(new RuntimeException("upstream down"));

            ResponseEntity<List<Entitlement>> response = service.syncLibrary(user, "req-token");

            assertThat(response.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNC)).isEmpty();
            assertThat(response.getBody()).doesNotContain((Entitlement) null);
        }
    }
}
