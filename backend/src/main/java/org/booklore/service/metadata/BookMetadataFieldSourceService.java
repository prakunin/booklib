package org.booklore.service.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataFieldSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads back the per-field provenance {@code BookMetadataUpdater} files, and attaches it to the book
 * DTOs the detail endpoints return.
 * <p>
 * Everything here is deliberately set-shaped rather than book-shaped. Attribution is stored in a side
 * table, so a naive read is one query per book; on the detail-by-ids endpoint that would be a query per
 * requested book, and this library holds over seven hundred thousand of them. {@link #attachTo(Collection)}
 * therefore issues exactly one statement for the whole collection regardless of its size, and no list
 * or page path calls this service at all — the badge only renders on the metadata screen, which loads a
 * single book.
 * <p>
 * A book with no rows gets an empty map, never null: absence of a row is a real answer ("this value's
 * origin is not recorded"), and the caller must not have to tell it apart from "nobody looked".
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookMetadataFieldSourceService {

    private final BookMetadataFieldSourceRepository fieldSourceRepository;

    /**
     * The provider behind each attributed field of one book. One query.
     */
    public Map<MetadataField, MetadataProvider> sourcesForBook(Long bookId) {
        if (bookId == null) {
            return Map.of();
        }
        return toSourceMap(fieldSourceRepository.findByBookId(bookId));
    }

    /**
     * The same, for a set of books, in a single query keyed by book id. Books without a single
     * attributed field are absent from the returned map; callers use an empty map for those.
     */
    public Map<Long, Map<MetadataField, MetadataProvider>> sourcesForBooks(Collection<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> distinctIds = bookIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<MetadataField, MetadataProvider>> byBookId = new HashMap<>();
        for (BookMetadataFieldSourceEntity row : fieldSourceRepository.findByBookIdIn(distinctIds)) {
            if (row.getBookId() == null || row.getFieldName() == null || row.getProvider() == null) {
                continue;
            }
            byBookId.computeIfAbsent(row.getBookId(), id -> new EnumMap<>(MetadataField.class))
                    .put(row.getFieldName(), row.getProvider());
        }
        return byBookId;
    }

    public void attachTo(Book book) {
        if (book == null || book.getMetadata() == null) {
            return;
        }
        book.getMetadata().setFieldSources(sourcesForBook(book.getId()));
    }

    /**
     * Attaches provenance to a whole collection of books with one query. Not for paginated lists — see
     * the class javadoc.
     */
    public void attachTo(Collection<Book> books) {
        if (books == null || books.isEmpty()) {
            return;
        }
        List<Book> attributable = books.stream()
                .filter(book -> book != null && book.getId() != null && book.getMetadata() != null)
                .toList();
        if (attributable.isEmpty()) {
            return;
        }
        Map<Long, Map<MetadataField, MetadataProvider>> byBookId = sourcesForBooks(
                attributable.stream().map(Book::getId).toList());
        for (Book book : attributable) {
            book.getMetadata().setFieldSources(byBookId.getOrDefault(book.getId(), Map.of()));
        }
    }

    private Map<MetadataField, MetadataProvider> toSourceMap(List<BookMetadataFieldSourceEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<MetadataField, MetadataProvider> sources = new EnumMap<>(MetadataField.class);
        for (BookMetadataFieldSourceEntity row : rows) {
            if (row.getFieldName() != null && row.getProvider() != null) {
                sources.put(row.getFieldName(), row.getProvider());
            }
        }
        return sources;
    }
}
