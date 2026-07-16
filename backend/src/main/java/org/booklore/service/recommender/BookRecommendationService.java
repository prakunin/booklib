package org.booklore.service.recommender;

import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.*;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookRecommendationService {

    private final BookRepository bookRepository;
    private final BookQueryService bookQueryService;
    private final BookMapper bookMapper;
    private final AuthenticationService authenticationService;
    private final BookRecommendationQueueService recommendationQueue;

    public List<BookRecommendation> getRecommendations(Long bookId, int limit) {
        BookEntity book = bookRepository.findByIdWithMetadata(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Set<BookRecommendationLite> recommendations = book.getSimilarBooksJson();
        if (recommendations == null) {
            recommendationQueue.enqueue(bookId, user.getUsername());
            return Collections.emptyList();
        }
        if (recommendations.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> recommendedBookIds = recommendations.stream()
                .map(BookRecommendationLite::getB)
                .collect(Collectors.toSet());
        Map<Long, BookEntity> recommendedBooks = bookQueryService.findAllWithMetadataByIds(recommendedBookIds).stream()
                .collect(Collectors.toMap(BookEntity::getId, Function.identity()));

        Set<Long> accessibleLibraryIds = user.getPermissions().isAdmin()
                ? null
                : user.getAssignedLibraries().stream().map(Library::getId).collect(Collectors.toSet());

        return recommendations.stream()
                .map(recommendation -> {
                    BookEntity recommendedBook = recommendedBooks.get(recommendation.getB());
                    if (recommendedBook == null || !isAccessible(recommendedBook, accessibleLibraryIds)) {
                        return null;
                    }
                    return new BookRecommendation(
                            bookMapper.toBookWithDescription(recommendedBook, false),
                            recommendation.getS());
                })
                .filter(Objects::nonNull)
                .limit(limit)
                .toList();
    }

    private boolean isAccessible(BookEntity book, Set<Long> accessibleLibraryIds) {
        return accessibleLibraryIds == null
                || (book.getLibrary() != null && accessibleLibraryIds.contains(book.getLibrary().getId()));
    }
}
