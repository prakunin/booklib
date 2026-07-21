package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import org.booklore.app.dto.UpdateProgressRequest;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.ReadProgressRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.service.annotation.InvalidateUserStats;
import org.booklore.service.book.BookService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppBookProgressService {

    private final BookRepository bookRepository;
    private final UserBookProgressRepository userBookProgressRepository;
    private final AuthenticationService authenticationService;
    private final BookService bookService;
    private final UserContentRestrictionRepository restrictionRepository;

    @InvalidateUserStats
    @Transactional
    // Deliberate use of the deprecated legacy per-format progress fields (dual-write compat); remove with the legacy columns.
    @SuppressWarnings("java:S1874")
    public void updateBookProgress(Long bookId, UpdateProgressRequest request) {
        validateAccessAndGetBook(bookId);

        ReadProgressRequest progressRequest = new ReadProgressRequest();
        progressRequest.setBookId(bookId);
        progressRequest.setFileProgress(request.getFileProgress());
        progressRequest.setEpubProgress(request.getEpubProgress());
        progressRequest.setPdfProgress(request.getPdfProgress());
        progressRequest.setCbxProgress(request.getCbxProgress());
        progressRequest.setAudiobookProgress(request.getAudiobookProgress());
        progressRequest.setDateFinished(request.getDateFinished());

        bookService.updateReadProgress(progressRequest);
    }

    @InvalidateUserStats
    @Transactional
    public void updateReadStatus(Long bookId, ReadStatus status) {
        UserBookProgressEntity progress = validateAccessAndGetProgress(bookId);

        progress.setReadStatus(status);
        progress.setReadStatusModifiedTime(Instant.now());

        if (status == ReadStatus.READ && progress.getDateFinished() == null) {
            progress.setDateFinished(Instant.now());
        }

        userBookProgressRepository.save(progress);
    }

    @Transactional
    public void updatePersonalRating(Long bookId, Integer rating) {
        UserBookProgressEntity progress = validateAccessAndGetProgress(bookId);

        progress.setPersonalRating(rating);
        userBookProgressRepository.save(progress);
    }

    private UserBookProgressEntity validateAccessAndGetProgress(Long bookId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        BookEntity book = validateBookAccess(user, bookId);
        Long userId = user.getId();

        return userBookProgressRepository
                .findByUserIdAndBookId(userId, bookId)
                .orElseGet(() -> createNewProgress(userId, book));
    }

    private BookEntity validateAccessAndGetBook(Long bookId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        return validateBookAccess(user, bookId);
    }

    private BookEntity validateBookAccess(BookLoreUser user, Long bookId) {
        Long userId = user.getId();
        Set<Long> accessibleLibraryIds = getAccessibleLibraryIds(user);

        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        validateLibraryAccess(accessibleLibraryIds, book.getLibrary().getId());
        Specification<BookEntity> sameBook = (root, query, cb) -> cb.equal(root.get("id"), book.getId());
        if (!bookRepository.exists(contentRestrictions(userId, accessibleLibraryIds == null).and(sameBook))) {
            throw ApiError.FORBIDDEN.createException("Access denied to this book");
        }
        return book;
    }

    private void validateLibraryAccess(Set<Long> accessibleLibraryIds, Long libraryId) {
        if (accessibleLibraryIds != null && !accessibleLibraryIds.contains(libraryId)) {
            throw ApiError.FORBIDDEN.createException("Access denied to this book");
        }
    }

    @SuppressWarnings("java:S1168") // three-state contract: null = admin/unrestricted (no library filter applied), empty = restricted to nothing, non-empty = specific IDs; callers branch on != null, so Set.of() would wrongly restrict admins to zero libraries
    private Set<Long> getAccessibleLibraryIds(BookLoreUser user) {
        if (user.getPermissions().isAdmin()) {
            return null;
        }
        if (user.getAssignedLibraries() == null || user.getAssignedLibraries().isEmpty()) {
            return Collections.emptySet();
        }
        return user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());
    }

    private Specification<BookEntity> contentRestrictions(Long userId, boolean isAdmin) {
        if (isAdmin) {
            return (root, query, cb) -> cb.conjunction();
        }
        return ContentRestrictionSpecification.from(restrictionRepository.findByUserId(userId));
    }

    private UserBookProgressEntity createNewProgress(Long userId, BookEntity book) {
        return UserBookProgressEntity.builder()
                .user(BookLoreUserEntity.builder().id(userId).build())
                .book(book)
                .build();
    }
}
