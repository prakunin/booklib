package org.booklore.service.inpx;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookCoverUpdateProjection;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.BookCoverService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class InpxCoverGenerationListener {

    private final BookCoverService bookCoverService;
    private final BookRepository bookRepository;
    private final NotificationService notificationService;
    /**
     * Dedups in-flight probes for the same book within this JVM only: it stops this single process
     * from firing off redundant concurrent archive reads for a book that's already being probed.
     * It provides no cross-process guarantee (a second app instance, or a request handled on a
     * different thread pool entirely, is not covered) and is not what makes concurrent persistence
     * safe - that correctness guarantee lives in {@link BookCoverService#tryGenerateMissingInpxCover}
     * via atomic conditional updates. Do not rely on this set for correctness, only for reducing
     * redundant work.
     */
    private final Set<Long> processingBookIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, Set<String>> waitingUsers = new ConcurrentHashMap<>();
    private final Cache<Long, Boolean> attemptedBooks = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(100_000)
            .build();

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(InpxCoverGenerationRequestedEvent event) {
        for (Long bookId : event.bookIds()) {
            processBookId(bookId, event);
        }
    }

    private void processBookId(Long bookId, InpxCoverGenerationRequestedEvent event) {
        if (bookId == null) {
            return;
        }
        Boolean previousAttemptGeneratedCover = attemptedBooks.getIfPresent(bookId);
        if (previousAttemptGeneratedCover != null) {
            if (previousAttemptGeneratedCover) {
                notifyUsers(bookId, Set.of(event.username()));
            }
            return;
        }
        waitingUsers.computeIfAbsent(bookId, ignored -> ConcurrentHashMap.newKeySet())
                .add(event.username());
        if (!processingBookIds.add(bookId)) {
            return;
        }
        generateCover(bookId);
    }

    private void generateCover(long bookId) {
        boolean generated = false;
        try {
            generated = bookCoverService.tryGenerateMissingInpxCover(bookId);
            attemptedBooks.put(bookId, generated);
        } catch (Exception e) {
            log.warn("Failed to lazily generate INPX cover for book {}: {}", bookId, e.getMessage());
            log.debug("Lazy INPX cover generation failure for book " + bookId, e);
        } finally {
            processingBookIds.remove(bookId);
            Set<String> usernames = waitingUsers.remove(bookId);
            if (generated && usernames != null && !usernames.isEmpty()) {
                notifyUsers(bookId, usernames);
            }
        }
    }

    private void notifyUsers(long bookId, Set<String> usernames) {
        List<BookCoverUpdateProjection> updates = bookRepository.findCoverUpdateInfoByIds(List.of(bookId));
        if (updates.isEmpty()) {
            return;
        }
        for (String username : usernames) {
            notificationService.sendMessageToUser(username, Topic.BOOKS_COVER_UPDATE, updates);
        }
    }
}
