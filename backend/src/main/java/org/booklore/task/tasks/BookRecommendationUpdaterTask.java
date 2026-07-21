package org.booklore.task.tasks;

import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookRecommendationLite;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.recommender.BookVectorService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookRecommendationUpdaterTask implements Task {

    private final BookQueryService bookQueryService;
    private final BookVectorService vectorService;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;

    private static final int RECOMMENDATION_LIMIT = 25;
    private static final int BATCH_SIZE = 500;
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 250;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(request.getTaskId())
                .taskType(TaskType.UPDATE_BOOK_RECOMMENDATIONS);

        String taskId = builder.build().getTaskId();

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        long lastNotificationTime = 0;

        lastNotificationTime = sendTaskProgressNotification(taskId, 0, "Starting book recommendation update", TaskStatus.IN_PROGRESS, lastNotificationTime, true);

        // Load books in batches, generate embeddings, store lightweight data only
        long totalBooks = bookQueryService.countAllNonDeleted();
        Map<Long, double[]> embeddings = new HashMap<>();
        Map<Long, String> seriesNames = new HashMap<>();

        lastNotificationTime = sendTaskProgressNotification(taskId, 2, String.format("Found %d books, generating embeddings in batches...", totalBooks), TaskStatus.IN_PROGRESS, lastNotificationTime, false);

        PhaseResult embeddingResult = generateEmbeddings(taskId, totalBooks, embeddings, seriesNames, lastNotificationTime);
        if (embeddingResult.cancelled()) {
            return buildCancelledResponse(builder, taskId, embeddingResult.lastNotificationTime());
        }
        lastNotificationTime = embeddingResult.lastNotificationTime();

        lastNotificationTime = sendTaskProgressNotification(taskId, 35, "Computing book similarities...", TaskStatus.IN_PROGRESS, lastNotificationTime, false);

        // Compute similarities using only in-memory vectors (no entities needed)
        Map<Long, Set<BookRecommendationLite>> allRecommendations = new HashMap<>();
        PhaseResult similarityResult = computeRecommendations(taskId, totalBooks, embeddings, seriesNames,
                allRecommendations, lastNotificationTime);
        if (similarityResult.cancelled()) {
            return buildCancelledResponse(builder, taskId, similarityResult.lastNotificationTime());
        }
        lastNotificationTime = similarityResult.lastNotificationTime();

        // Catch a cancellation that arrived while processing the final target, before publishing.
        if (cancellationManager.isTaskCancelled(taskId)) {
            return buildCancelledResponse(builder, taskId, lastNotificationTime);
        }

        // Save recommendations in batches
        lastNotificationTime = sendTaskProgressNotification(taskId, 85, String.format("Saving recommendations for %d books...", allRecommendations.size()), TaskStatus.IN_PROGRESS, lastNotificationTime, false);

        bookQueryService.saveRecommendationsInBatches(allRecommendations, BATCH_SIZE);

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        sendTaskProgressNotification(taskId, 100, String.format("Updated recommendations for %d books in %d ms", totalBooks, endTime - startTime), TaskStatus.COMPLETED, lastNotificationTime, true);

        return builder
                .status(TaskStatus.COMPLETED)
                .build();
    }

    private record PhaseResult(boolean cancelled, long lastNotificationTime) {}

    private PhaseResult generateEmbeddings(String taskId, long totalBooks, Map<Long, double[]> embeddings,
                                           Map<Long, String> seriesNames, long lastNotificationTime) {
        int embeddingProgress = 0;
        int batchPage = 0;
        boolean moreBatches = true;
        while (moreBatches) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                return new PhaseResult(true, lastNotificationTime);
            }
            List<BookEntity> batch = bookQueryService.getAllFullBookEntitiesBatch(
                    PageRequest.of(batchPage, BATCH_SIZE));
            if (batch.isEmpty()) {
                moreBatches = false;
                continue;
            }
            embeddingProgress = accumulateEmbeddings(batch, embeddings, seriesNames, embeddingProgress);
            saveBatchEmbeddings(batch, embeddings);

            int progress = 5 + (int) (embeddingProgress * 30L / totalBooks);
            lastNotificationTime = sendTaskProgressNotification(taskId, progress,
                    String.format("Generated embeddings: %d/%d books", embeddingProgress, totalBooks),
                    TaskStatus.IN_PROGRESS, lastNotificationTime, false);

            moreBatches = batch.size() >= BATCH_SIZE;
            if (moreBatches) {
                batchPage++;
            }
        }
        return new PhaseResult(false, lastNotificationTime);
    }

    private int accumulateEmbeddings(List<BookEntity> batch, Map<Long, double[]> embeddings,
                                     Map<Long, String> seriesNames, int embeddingProgress) {
        for (BookEntity book : batch) {
            double[] embedding = vectorService.generateEmbedding(book);
            embeddings.put(book.getId(), embedding);

            // Store series name for similarity filtering
            String series = Optional.ofNullable(book.getMetadata())
                    .map(BookMetadataEntity::getSeriesName)
                    .map(String::toLowerCase)
                    .orElse(null);
            if (series != null) {
                seriesNames.put(book.getId(), series);
            }

            embeddingProgress++;
        }
        return embeddingProgress;
    }

    private void saveBatchEmbeddings(List<BookEntity> batch, Map<Long, double[]> embeddings) {
        // Save embedding vectors for this batch within a transaction
        Map<Long, String> batchEmbeddingJson = new HashMap<>();
        for (BookEntity book : batch) {
            batchEmbeddingJson.put(book.getId(), vectorService.serializeVector(embeddings.get(book.getId())));
        }
        bookQueryService.compareAndSaveEmbeddings(batchEmbeddingJson);
    }

    private PhaseResult computeRecommendations(String taskId, long totalBooks, Map<Long, double[]> embeddings,
                                               Map<Long, String> seriesNames,
                                               Map<Long, Set<BookRecommendationLite>> allRecommendations,
                                               long lastNotificationTime) {
        Set<Long> allBookIds = embeddings.keySet();
        int processedBooks = 0;
        for (Map.Entry<Long, double[]> entry : embeddings.entrySet()) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                return new PhaseResult(true, lastNotificationTime);
            }
            Long targetId = entry.getKey();
            try {
                double[] targetVector = entry.getValue();
                if (targetVector == null) continue;

                Set<BookRecommendationLite> recommendations =
                        computeRecommendationsForBook(targetId, targetVector, allBookIds, embeddings, seriesNames);
                allRecommendations.put(targetId, recommendations);

            } catch (Exception e) {
                log.error("{}: Error computing similarity for book ID {}", getTaskType(), targetId, e);
            }

            processedBooks++;
            if (processedBooks % 10 == 0 || processedBooks == totalBooks) {
                int progress = 35 + (int) (processedBooks * 50L / totalBooks);
                lastNotificationTime = sendTaskProgressNotification(taskId, progress,
                        String.format("Computing similarities: %d/%d books", processedBooks, totalBooks),
                        TaskStatus.IN_PROGRESS, lastNotificationTime, false);
            }
        }
        return new PhaseResult(false, lastNotificationTime);
    }

    private Set<BookRecommendationLite> computeRecommendationsForBook(Long targetId, double[] targetVector,
                                                                     Set<Long> allBookIds, Map<Long, double[]> embeddings,
                                                                     Map<Long, String> seriesNames) {
        String targetSeries = seriesNames.get(targetId);

        List<BookVectorService.ScoredBook> candidates = allBookIds.stream()
                .filter(candidateId -> !candidateId.equals(targetId))
                .filter(candidateId -> {
                    if (targetSeries == null) return true;
                    String candidateSeries = seriesNames.get(candidateId);
                    return !targetSeries.equals(candidateSeries);
                })
                .map(candidateId -> {
                    double[] candidateVector = embeddings.get(candidateId);
                    double similarity = vectorService.cosineSimilarity(targetVector, candidateVector);
                    return new BookVectorService.ScoredBook(candidateId, similarity);
                })
                .filter(scored -> scored.getScore() > 0.1)
                .toList();

        List<BookVectorService.ScoredBook> topSimilar = vectorService.findTopKSimilar(
                targetVector,
                candidates,
                RECOMMENDATION_LIMIT
        );

        return topSimilar.stream()
                .map(scored -> new BookRecommendationLite(scored.getBookId(), scored.getScore()))
                .collect(Collectors.toSet());
    }

    // The embedding and similarity phases loop over the whole library, so honour a user cancellation
    // at each checkpoint (matching LibraryRescanTask) instead of grinding through every book.
    private TaskCreateResponse buildCancelledResponse(TaskCreateResponse.TaskCreateResponseBuilder builder,
                                                      String taskId, long lastNotificationTime) {
        log.info("{}: Task {} stopped before completion (cancelled or interrupted)", getTaskType(), taskId);
        sendTaskProgressNotification(taskId, 0, "Recommendation update cancelled", TaskStatus.CANCELLED, lastNotificationTime, true);
        return builder.status(TaskStatus.CANCELLED).build();
    }

    private long sendTaskProgressNotification(String taskId, int progress, String message, TaskStatus taskStatus, long lastNotificationTime, boolean force) {
        long currentTime = System.currentTimeMillis();

        // Send if forced (start/end) or if enough time has passed
        if (force || (currentTime - lastNotificationTime) >= MIN_NOTIFICATION_INTERVAL_MS) {
            try {
                TaskProgressPayload payload = TaskProgressPayload.builder()
                        .taskId(taskId)
                        .taskType(TaskType.UPDATE_BOOK_RECOMMENDATIONS)
                        .message(message)
                        .progress(progress)
                        .taskStatus(taskStatus)
                        .build();

                notificationService.sendMessage(Topic.TASK_PROGRESS, payload);
                return currentTime;
            } catch (Exception e) {
                log.error("Failed to send task progress notification for taskId={}: {}", taskId, e.getMessage(), e);
            }
        }

        return lastNotificationTime;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.UPDATE_BOOK_RECOMMENDATIONS;
    }
}
