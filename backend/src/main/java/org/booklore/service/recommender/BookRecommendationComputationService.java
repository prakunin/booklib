package org.booklore.service.recommender;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookRecommendationLite;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookEmbeddingProjection;
import org.booklore.service.book.BookQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookRecommendationComputationService {

    private static final int BATCH_SIZE = 500;
    private static final int CANDIDATE_MULTIPLIER = 20;
    private static final int MAX_BOOKS_PER_AUTHOR = 3;

    private final BookSimilarityService similarityService;
    private final BookVectorService vectorService;
    private final BookRepository bookRepository;
    private final BookQueryService bookQueryService;

    public void computeAndStore(long bookId, int limit) {
        BookEntity target = bookRepository.findByIdWithMetadata(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        int candidateLimit = Math.max(limit, limit * CANDIDATE_MULTIPLIER);
        PriorityQueue<ScoredCandidate> topCandidates = new PriorityQueue<>(Comparator.comparingDouble(ScoredCandidate::score));

        String targetVectorJson = Optional.ofNullable(target.getMetadata())
                .map(BookMetadataEntity::getEmbeddingVector)
                .orElse(null);
        double[] targetVector = vectorService.deserializeVector(targetVectorJson);

        if (targetVector != null) {
            scoreEmbeddingCandidates(bookId, target, targetVector, candidateLimit, topCandidates);
        } else {
            scoreEntityCandidates(bookId, target, candidateLimit, topCandidates);
        }

        Set<BookRecommendationLite> recommendations = selectRecommendations(topCandidates, limit);
        bookQueryService.saveRecommendations(bookId, recommendations);
        log.info("Computed {} recommendations for book {} using bounded batches", recommendations.size(), bookId);
    }

    private void scoreEntityCandidates(long bookId, BookEntity target, int candidateLimit,
                                       PriorityQueue<ScoredCandidate> topCandidates) {
        String targetSeries = normalizedSeries(target);
        long afterId = 0;
        while (true) {
            List<BookEntity> batch = bookQueryService.getRecommendationCandidatesAfterId(
                    bookId, afterId, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                return;
            }

            for (BookEntity candidate : batch) {
                afterId = Math.max(afterId, candidate.getId());
                if (targetSeries != null && targetSeries.equals(normalizedSeries(candidate))) {
                    continue;
                }
                double score = similarityService.calculateSimilarity(target, candidate);
                if (score > 0.0) {
                    retainCandidate(topCandidates, new ScoredCandidate(candidate.getId(), score), candidateLimit);
                }
            }

        }
    }

    private void scoreEmbeddingCandidates(long bookId, BookEntity target, double[] targetVector, int candidateLimit,
                                          PriorityQueue<ScoredCandidate> topCandidates) {
        String targetSeries = normalizedSeries(target);
        long afterId = 0;
        while (true) {
            List<BookEmbeddingProjection> batch = bookQueryService.getEmbeddingCandidatesAfterId(
                    bookId, afterId, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                return;
            }

            for (BookEmbeddingProjection candidate : batch) {
                afterId = Math.max(afterId, candidate.getBookId());
                String candidateSeries = Optional.ofNullable(candidate.getSeriesName()).map(String::toLowerCase).orElse(null);
                if (targetSeries != null && targetSeries.equals(candidateSeries)) {
                    continue;
                }
                double[] candidateVector = vectorService.deserializeVector(candidate.getEmbeddingVector());
                double score = vectorService.cosineSimilarity(targetVector, candidateVector);
                if (score > 0.1) {
                    retainCandidate(topCandidates, new ScoredCandidate(candidate.getBookId(), score), candidateLimit);
                }
            }

        }
    }

    private void retainCandidate(PriorityQueue<ScoredCandidate> candidates, ScoredCandidate candidate, int limit) {
        if (candidates.size() < limit) {
            candidates.offer(candidate);
            return;
        }
        ScoredCandidate lowest = candidates.peek();
        if (lowest != null && candidate.score() > lowest.score()) {
            candidates.poll();
            candidates.offer(candidate);
        }
    }

    private Set<BookRecommendationLite> selectRecommendations(PriorityQueue<ScoredCandidate> candidates, int limit) {
        List<ScoredCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> candidateIds = sorted.stream().map(ScoredCandidate::bookId).collect(Collectors.toSet());
        Map<Long, BookEntity> books = bookQueryService.findAllWithMetadataByIds(candidateIds).stream()
                .collect(Collectors.toMap(BookEntity::getId, book -> book));

        Map<String, Integer> authorCounts = new HashMap<>();
        Set<BookRecommendationLite> result = new LinkedHashSet<>();
        for (ScoredCandidate candidate : sorted) {
            BookEntity book = books.get(candidate.bookId());
            if (book == null) {
                continue;
            }
            Set<String> authors = authorNames(book);
            boolean allowed = authors.stream().allMatch(name -> authorCounts.getOrDefault(name, 0) < MAX_BOOKS_PER_AUTHOR);
            if (allowed) {
                result.add(new BookRecommendationLite(candidate.bookId(), candidate.score()));
                authors.forEach(name -> authorCounts.merge(name, 1, Integer::sum));
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private String normalizedSeries(BookEntity book) {
        return Optional.ofNullable(book.getMetadata())
                .map(BookMetadataEntity::getSeriesName)
                .map(String::toLowerCase)
                .orElse(null);
    }

    private Set<String> authorNames(BookEntity book) {
        if (book.getMetadata() == null || book.getMetadata().getAuthors() == null) {
            return Collections.emptySet();
        }
        return book.getMetadata().getAuthors().stream()
                .map(AuthorEntity::getName)
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private record ScoredCandidate(long bookId, double score) {
    }
}
