package org.booklore.service.recommender;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class BookRecommendationQueueService {

    private static final int RECOMMENDATION_LIMIT = 25;

    private final BookRecommendationComputationService computationService;
    private final NotificationService notificationService;
    @Qualifier("bookRecommendationExecutor")
    private final AsyncTaskExecutor recommendationExecutor;
    private final Set<Long> scheduledBookIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, Set<String>> waitingUsers = new ConcurrentHashMap<>();

    public BookRecommendationQueueService(
            BookRecommendationComputationService computationService,
            NotificationService notificationService,
            @Qualifier("bookRecommendationExecutor") AsyncTaskExecutor recommendationExecutor) {
        this.computationService = computationService;
        this.notificationService = notificationService;
        this.recommendationExecutor = recommendationExecutor;
    }

    public void enqueue(long bookId, String username) {
        waitingUsers.computeIfAbsent(bookId, ignored -> ConcurrentHashMap.newKeySet()).add(username);
        if (!scheduledBookIds.add(bookId)) {
            return;
        }

        try {
            recommendationExecutor.execute(() -> process(bookId));
        } catch (TaskRejectedException _) {
            scheduledBookIds.remove(bookId);
            waitingUsers.remove(bookId);
            throw ApiError.RECOMMENDATION_QUEUE_FULL.createException();
        }
    }

    private void process(long bookId) {
        boolean completed = false;
        try {
            computationService.computeAndStore(bookId, RECOMMENDATION_LIMIT);
            completed = true;
        } catch (Exception e) {
            log.error("Failed to compute queued recommendations for book {}", bookId, e);
        } finally {
            scheduledBookIds.remove(bookId);
            Set<String> usernames = waitingUsers.remove(bookId);
            if (completed && usernames != null) {
                for (String username : usernames) {
                    notificationService.sendMessageToUser(username, Topic.BOOK_RECOMMENDATIONS_UPDATE, bookId);
                }
            }
        }
    }
}
