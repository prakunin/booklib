package org.booklore.service.browse;

import lombok.RequiredArgsConstructor;
import org.booklore.browse.FacetLogic;
import org.booklore.browse.SortParser;
import org.booklore.browse.SortTerm;
import org.booklore.app.dto.AppPageResponse;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.service.book.BookQueryService;
import org.booklore.service.progress.ReadingProgressService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookBrowseService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuthenticationService authenticationService;
    private final BookQueryService bookQueryService;
    private final ReadingProgressService readingProgressService;
    private final BookFilterSpecifications filterSpecifications;
    private final BookSortRegistry sortRegistry;

    public AppPageResponse<Book> browse(String sort, List<String> facet, String facetLogicParam, String query, Pageable pageable) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user.getId();
        boolean isAdmin = user.getPermissions().isAdmin();

        Map<String, List<String>> facets = BookFilterSpecifications.parseFacets(facet);
        FacetLogic facetLogic = FacetLogic.from(facetLogicParam);

        int limit = pageable.getPageSize();
        if (limit <= 0) {
            throw ApiError.INVALID_INPUT.createException("Page size must be positive.");
        }
        limit = Math.min(limit, MAX_PAGE_SIZE);

        List<SortTerm> sortTerms = SortParser.parse(sort, sortRegistry.registry().keys());
        Specification<BookEntity> filter = filterSpecifications.base(query, facets, facetLogic, userId, isAdmin, BookFilterSpecifications.libraryIds(user), null);
        Specification<BookEntity> spec = BookSortSpecifications.withSort(filter, sortRegistry, sortTerms, userId);

        Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), limit);
        Page<Book> page = bookQueryService.findBooksPaged(spec, pageRequest, userId);
        enrich(page.getContent(), userId);

        return AppPageResponse.fromPage(page);
    }

    private void enrich(List<Book> books, Long userId) {
        Set<Long> bookIds = books.stream().map(Book::getId).collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> progress = readingProgressService.fetchUserProgress(userId, bookIds);
        Map<Long, UserBookFileProgressEntity> fileProgress = readingProgressService.fetchUserFileProgress(userId, bookIds);
        for (Book book : books) {
            readingProgressService.enrichBookWithProgress(book, progress.get(book.getId()), fileProgress.get(book.getId()));
        }
    }
}
