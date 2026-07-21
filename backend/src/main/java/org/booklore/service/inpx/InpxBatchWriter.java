package org.booklore.service.inpx;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.CategoryRepository;
import org.booklore.service.author.AuthorLocalResolver;
import org.booklore.util.AuthorNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InpxBatchWriter {

    private final BookRepository bookRepository;
    private final BookFileRepository bookFileRepository;
    private final CategoryRepository categoryRepository;
    private final AuthorLocalResolver authorLocalResolver;
    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * Persists one batch in its own transaction, so neither memory nor transaction
     * duration grows with the size of the index.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult persist(List<InpxBookDto> batch, long libraryId, long libraryPathId, InpxScanCaches caches) {
        if (batch.isEmpty()) {
            return new BatchResult(0, 0);
        }

        List<String> keys = batch.stream().map(this::archiveKey).toList();
        Set<String> existing = findExistingKeys(libraryId, batch, keys);
        backfillExistingFileSizes(libraryId, batch);

        LibraryEntity library = entityManager.getReference(LibraryEntity.class, libraryId);
        LibraryPathEntity libraryPath = entityManager.getReference(LibraryPathEntity.class, libraryPathId);

        // Ids for authors/categories that this batch's transaction just inserted. They must
        // not be promoted into the scan-wide `caches` until this transaction actually commits:
        // if the batch rolls back (e.g. a constraint violation on some other book at flush
        // time), a cached-but-never-persisted id would poison every later batch that reuses
        // the same name. Within this batch they are still reused via pendingAuthors/pendingCategories.
        Map<String, Long> pendingAuthors = new HashMap<>();
        Map<String, Long> pendingCategories = new HashMap<>();

        List<BookEntity> books = new ArrayList<>(batch.size());
        Set<String> seenInBatch = new HashSet<>();
        int skipped = 0;
        for (InpxBookDto source : batch) {
            String key = archiveKey(source);
            if (existing.contains(key) || !seenInBatch.add(key)) {
                skipped++;
                continue;
            }
            books.add(toBook(source, library, libraryPath, caches, pendingAuthors, pendingCategories));
        }

        if (!books.isEmpty()) {
            bookRepository.saveAll(books);
            bookRepository.flush();
            books.forEach(entityManager::detach);
        }
        registerCachePromotion(caches, pendingAuthors, pendingCategories);
        return new BatchResult(books.size(), skipped);
    }

    private void backfillExistingFileSizes(long libraryId, List<InpxBookDto> batch) {
        Map<String, Long> sizesByKey = batch.stream()
                .filter(source -> source.getFileSizeKb() != null)
                .collect(Collectors.toMap(this::archiveKey, InpxBookDto::getFileSizeKb, (first, ignored) -> first));
        if (sizesByKey.isEmpty()) {
            return;
        }

        Set<String> archives = batch.stream().map(InpxBookDto::getArchiveName).collect(Collectors.toSet());
        Set<String> entries = batch.stream().map(this::entryName).collect(Collectors.toSet());
        for (BookFileEntity file : bookFileRepository.findArchiveEntriesMissingSize(libraryId, archives, entries)) {
            Long size = sizesByKey.get(file.getSourceArchive() + "|" + file.getSourceArchiveEntry());
            if (size != null) {
                file.setFileSizeKb(size);
            }
        }
    }

    /**
     * Looks up which of this batch's archive|entry keys already exist for the library, using a
     * query on the raw (indexed) columns rather than CONCAT(sourceArchive, '|', sourceArchiveEntry)
     * so MariaDB can use idx_book_file_archive_source. The archives/entries IN-lists can match a
     * cross product wider than the batch's own keys (e.g. archive A + entry from archive B), so
     * the result is intersected with the batch's own keys before being returned.
     */
    private Set<String> findExistingKeys(long libraryId, List<InpxBookDto> batch, List<String> keys) {
        Set<String> archives = batch.stream().map(InpxBookDto::getArchiveName).collect(Collectors.toSet());
        Set<String> entries = batch.stream().map(this::entryName).collect(Collectors.toSet());
        List<Object[]> rows = bookFileRepository.findExistingArchiveEntries(libraryId, archives, entries);

        Set<String> candidateKeys = new HashSet<>(keys);
        Set<String> existing = new HashSet<>();
        for (Object[] row : rows) {
            String key = row[0] + "|" + row[1];
            if (candidateKeys.contains(key)) {
                existing.add(key);
            }
        }
        return existing;
    }

    /**
     * Promotes ids for newly-created authors/categories into the scan-wide cache only once
     * this batch's transaction has committed. When there is no active transaction synchronization
     * (e.g. this method invoked directly, outside the {@code @Transactional} proxy) there is no
     * commit/rollback to wait for, so the ids are safe to promote immediately.
     */
    private void registerCachePromotion(InpxScanCaches caches, Map<String, Long> pendingAuthors,
                                        Map<String, Long> pendingCategories) {
        if (pendingAuthors.isEmpty() && pendingCategories.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            caches.authors().putAll(pendingAuthors);
            caches.categories().putAll(pendingCategories);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                caches.authors().putAll(pendingAuthors);
                caches.categories().putAll(pendingCategories);
            }
        });
    }

    private BookEntity toBook(InpxBookDto source, LibraryEntity library, LibraryPathEntity libraryPath,
                              InpxScanCaches caches, Map<String, Long> pendingAuthors, Map<String, Long> pendingCategories) {
        Instant now = Instant.now();
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(now)
                .scannedOn(now)
                .build();

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .title(source.getTitle())
                .seriesName(blankToNull(source.getSeries()))
                .seriesNumber(parseSeriesNumber(source.getSeriesNumber()))
                .publishedDate(parseDate(source.getDate()))
                .language(blankToNull(source.getLanguage()))
                .rating(source.getRating())
                .authors(resolveAuthors(source.getAuthors(), caches.authors(), pendingAuthors))
                .categories(resolveCategories(source.getGenres(), caches.categories(), pendingCategories))
                .build();
        book.setMetadata(metadata);

        String entryName = entryName(source);
        BookFileEntity file = BookFileEntity.builder()
                .book(book)
                .fileName(entryName)
                .fileSubPath("")
                .isBookFormat(true)
                .bookType(BookFileType.FB2)
                .sourceArchive(source.getArchiveName())
                .sourceArchiveEntry(entryName)
                .fileSizeKb(source.getFileSizeKb())
                .addedOn(now)
                .build();
        book.setBookFiles(new ArrayList<>(List.of(file)));
        book.setHasFiles(true);
        return book;
    }

    private List<AuthorEntity> resolveAuthors(List<String> names, Map<String, Long> committedCache,
                                              Map<String, Long> pendingCache) {
        List<AuthorEntity> result = new ArrayList<>();
        for (String name : names) {
            String cleaned = AuthorNames.cleanDisplayName(name);
            if (cleaned.isEmpty()) {
                continue;
            }
            // Cache key must be the EXACT cleaned name, not AuthorNames.normalizeKey(cleaned):
            // the resolver's identity is AuthorLocalResolver.resolve(name) -> findByName(cleanDisplayName(name)),
            // and normalizeKey additionally folds diacritics/case/punctuation, which would collapse
            // genuinely distinct authors (e.g. "Stanislaw Lem" vs "Stanisław Lem") into one cache entry.
            String key = cleaned;
            Long id = committedCache.get(key);
            if (id == null) {
                id = pendingCache.get(key);
            }
            if (id == null) {
                Optional<AuthorEntity> resolved = authorLocalResolver.resolve(name);
                if (resolved.isEmpty()) {
                    continue;
                }
                // Not yet promoted scan-wide: reusable within this batch, but only promoted
                // scan-wide once this batch's transaction commits (see registerCachePromotion).
                id = resolved.get().getId();
                pendingCache.put(key, id);
            }
            result.add(entityManager.getReference(AuthorEntity.class, id));
        }
        return result;
    }

    private Set<CategoryEntity> resolveCategories(List<String> names, Map<String, Long> committedCache,
                                                   Map<String, Long> pendingCache) {
        Set<CategoryEntity> result = new HashSet<>();
        for (String name : names) {
            String key = normalize(name);
            if (name == null || key.isBlank()) {
                continue;
            }
            Long id = committedCache.get(key);
            if (id == null) {
                id = pendingCache.get(key);
            }
            if (id == null) {
                String strippedName = name.strip();
                // Plain equality, not findByNameIgnoreCase: same reasoning as resolveAuthors -
                // category.name is already case-insensitive collation and uniquely indexed, so
                // upper(name)=upper(?) would defeat the index and force a full scan per lookup.
                Optional<CategoryEntity> found = categoryRepository.findByName(strippedName);
                if (found.isPresent()) {
                    // Already committed: safe to cache scan-wide immediately.
                    id = found.get().getId();
                    committedCache.put(key, id);
                } else {
                    // Not yet committed: reusable within this batch, but only promoted
                    // scan-wide once this batch's transaction commits.
                    id = categoryRepository.save(CategoryEntity.builder().name(strippedName).build()).getId();
                    pendingCache.put(key, id);
                }
            }
            result.add(entityManager.getReference(CategoryEntity.class, id));
        }
        return result;
    }

    private String entryName(InpxBookDto source) {
        return source.getFileName() + "." + source.getExtension();
    }

    private String archiveKey(InpxBookDto source) {
        return source.getArchiveName() + "|" + entryName(source);
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Float parseSeriesNumber(String value) {
        try {
            return value == null || value.isBlank() ? null : Float.valueOf(value.replace(',', '.'));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    public record BatchResult(int added, int skipped) {
    }
}
