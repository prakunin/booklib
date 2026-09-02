package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InpxArchiveReconciliationService {

    private static final int ARCHIVE_SOURCE_BATCH_SIZE = 2_000;
    private static final int ARCHIVE_QUERY_BATCH_SIZE = 500;
    private static final Set<String> SUPPORT_EXTENSIONS = Set.of(
            "css", "js", "gif", "jpg", "jpeg", "png", "webp", "svg", "xml", "xsl", "xslt",
            "woff", "woff2", "ttf", "otf", "eot");

    private final BookFileRepository bookFileRepository;
    private final BookRepository bookRepository;
    private final ArchiveEntryMetadataRecognizer entryMetadataRecognizer;
    private final InpxArchiveRemovalBatchService archiveRemovalBatchService;

    public RemovalResult removeBooksFromMissingArchives(long libraryId, Set<String> presentArchiveNames,
                                                        BooleanSupplier cancellation) {
        Set<String> persistedArchiveNames = loadPersistedArchiveNames(libraryId);
        if (persistedArchiveNames.isEmpty()) {
            return RemovalResult.EMPTY;
        }
        if (presentArchiveNames.isEmpty()) {
            log.warn("Skipping removal of books from {} archived sources in INPX library {} because the archive snapshot is empty",
                    persistedArchiveNames.size(), libraryId);
            return RemovalResult.EMPTY;
        }

        Set<String> missingArchiveNames = new HashSet<>(persistedArchiveNames);
        missingArchiveNames.removeAll(presentArchiveNames);
        if (missingArchiveNames.isEmpty()) {
            return RemovalResult.EMPTY;
        }

        int removed = 0;
        List<String> missingArchives = List.copyOf(missingArchiveNames);
        for (int start = 0; start < missingArchives.size(); start += ARCHIVE_QUERY_BATCH_SIZE) {
            Set<String> queryArchives = Set.copyOf(missingArchives.subList(
                    start, Math.min(start + ARCHIVE_QUERY_BATCH_SIZE, missingArchives.size())));
            long afterId = 0;
            while (true) {
                if (cancellation.getAsBoolean()) {
                    return new RemovalResult(removed, true);
                }
                InpxArchiveRemovalBatchService.RemovalBatch batch = archiveRemovalBatchService.removeNext(
                        libraryId, queryArchives, missingArchiveNames, afterId);
                if (batch.scanned() == 0) {
                    break;
                }
                removed += batch.removed();
                afterId = batch.lastBookId();
            }
        }
        log.info("Removed {} books from {} missing archives in INPX library {}",
                removed, missingArchiveNames.size(), libraryId);
        return new RemovalResult(removed, cancellation.getAsBoolean());
    }

    private Set<String> loadPersistedArchiveNames(long libraryId) {
        Set<String> archiveNames = new HashSet<>();
        long afterId = 0;
        while (true) {
            List<Object[]> rows = bookFileRepository.findArchiveSourcesAfterId(
                    libraryId, afterId, PageRequest.of(0, ARCHIVE_SOURCE_BATCH_SIZE));
            if (rows.isEmpty()) {
                return archiveNames;
            }
            rows.forEach(row -> archiveNames.add((String) row[1]));
            afterId = (Long) rows.getLast()[0];
        }
    }

    public record RemovalResult(int removed, boolean cancelled) {
        private static final RemovalResult EMPTY = new RemovalResult(0, false);
    }

    /** Retires legacy archive cards only after at least one descendant locator is persisted. */
    @Transactional
    public int retireObsoleteGenericContainers(long libraryId, Collection<String> archiveNames) {
        if (archiveNames.isEmpty()) {
            return 0;
        }
        Map<String, List<BookFileEntity>> filesByArchive = bookFileRepository
                .findBookFilesByArchives(libraryId, archiveNames).stream()
                .collect(Collectors.groupingBy(BookFileEntity::getSourceArchive));
        Map<Long, BookEntity> booksToRetire = new LinkedHashMap<>();
        for (List<BookFileEntity> files : filesByArchive.values()) {
            boolean nestedLeafPersisted = files.stream().anyMatch(file ->
                    !Boolean.TRUE.equals(file.getBook().getDeleted())
                            && NestedArchiveLocator.isNested(file.getSourceArchiveEntry(), file.getFileName()));
            if (!nestedLeafPersisted) {
                continue;
            }
            for (BookFileEntity file : files) {
                if (!Boolean.TRUE.equals(file.getBook().getDeleted())
                        && file.getBookType() == BookFileType.OTHER
                        && !NestedArchiveLocator.isNested(file.getSourceArchiveEntry(), file.getFileName())
                        && entryMetadataRecognizer.isGenericArchive(file.getSourceArchiveEntry())) {
                    booksToRetire.put(file.getBook().getId(), file.getBook());
                }
            }
        }
        Instant deletedAt = Instant.now();
        booksToRetire.values().forEach(book -> {
            book.setDeleted(true);
            book.setDeletedAt(deletedAt);
        });
        if (!booksToRetire.isEmpty()) {
            bookRepository.saveAll(new ArrayList<>(booksToRetire.values()));
        }
        return booksToRetire.size();
    }

    /** Normalizes rows created by the former leaf-per-asset scanner during an explicit full scan. */
    @Transactional
    public ReconciliationResult reconcileNestedPublications(long libraryId, String archiveName) {
        List<BookFileEntity> files = bookFileRepository.findBookFilesByArchives(libraryId, List.of(archiveName));
        List<BookFileEntity> filesToUpdate = new ArrayList<>();
        Set<List<String>> htmlParents = new HashSet<>();
        Set<List<String>> comicContainers = new HashSet<>();

        int promotedHtml = promoteHtmlPublications(files, filesToUpdate, htmlParents, comicContainers);
        promoteImageOnlyContainers(files, filesToUpdate, comicContainers);
        Map<Long, BookEntity> booksToRetire = publicationAssetBooks(files, htmlParents, comicContainers);

        Instant deletedAt = Instant.now();
        booksToRetire.values().forEach(book -> {
            book.setDeleted(true);
            book.setDeletedAt(deletedAt);
        });
        if (!filesToUpdate.isEmpty()) {
            bookFileRepository.saveAll(filesToUpdate);
        }
        if (!booksToRetire.isEmpty()) {
            bookRepository.saveAll(new ArrayList<>(booksToRetire.values()));
        }
        return new ReconciliationResult(promotedHtml, booksToRetire.size());
    }

    /**
     * First pass: nested HTML leaves become HTML books. Their parent chains, and the containers of
     * books already typed as comics, are remembered for the asset pass.
     *
     * @return how many rows were promoted from OTHER to HTML
     */
    private int promoteHtmlPublications(List<BookFileEntity> files, List<BookFileEntity> filesToUpdate,
                                        Set<List<String>> htmlParents, Set<List<String>> comicContainers) {
        int promotedHtml = 0;
        for (BookFileEntity file : files) {
            if (isDeletedBook(file)) {
                continue;
            }
            List<String> chain = decode(file);
            if (isHtml(file.getFileName()) && chain.size() >= 2) {
                htmlParents.add(parentOf(chain));
                if (file.getBookType() == BookFileType.OTHER) {
                    file.setBookType(BookFileType.HTML);
                    filesToUpdate.add(file);
                    promotedHtml++;
                }
            }
            if (file.getBookType() == BookFileType.CBX
                    && entryMetadataRecognizer.isGenericArchive(chain.getLast())) {
                comicContainers.add(chain);
            }
        }
        return promotedHtml;
    }

    /** Second pass: a generic archive whose descendants are images and comic resources only is a comic. */
    private void promoteImageOnlyContainers(List<BookFileEntity> files, List<BookFileEntity> filesToUpdate,
                                            Set<List<String>> comicContainers) {
        for (BookFileEntity container : files) {
            if (isDeletedBook(container)
                    || container.getBookType() != BookFileType.OTHER
                    || !entryMetadataRecognizer.isGenericArchive(container.getFileName())) {
                continue;
            }
            List<String> containerChain = decode(container);
            List<BookFileEntity> descendants = files.stream()
                    .filter(file -> !isDeletedBook(file))
                    .filter(file -> startsWith(decode(file), containerChain))
                    .toList();
            boolean hasImage = descendants.stream().anyMatch(file -> isImage(file.getFileName()));
            boolean onlyComicResources = descendants.stream().allMatch(file -> isSupport(file.getFileName()));
            if (hasImage && onlyComicResources) {
                container.setBookType(BookFileType.CBX);
                filesToUpdate.add(container);
                comicContainers.add(containerChain);
            }
        }
    }

    /** Third pass: a support asset that belongs to an HTML publication or a comic is not a book of its own. */
    private Map<Long, BookEntity> publicationAssetBooks(List<BookFileEntity> files, Set<List<String>> htmlParents,
                                                        Set<List<String>> comicContainers) {
        Map<Long, BookEntity> booksToRetire = new LinkedHashMap<>();
        for (BookFileEntity file : files) {
            if (isDeletedBook(file) || !isSupport(file.getFileName())) {
                continue;
            }
            List<String> chain = decode(file);
            if (chain.size() >= 2 && isPublicationAsset(chain, htmlParents, comicContainers) && isOnlyBookFile(file)) {
                booksToRetire.put(file.getBook().getId(), file.getBook());
            }
        }
        return booksToRetire;
    }

    private boolean isPublicationAsset(List<String> chain, Set<List<String>> htmlParents,
                                       Set<List<String>> comicContainers) {
        return htmlParents.contains(parentOf(chain))
                || comicContainers.stream().anyMatch(container -> startsWith(chain, container));
    }

    private static List<String> parentOf(List<String> chain) {
        return List.copyOf(chain.subList(0, chain.size() - 1));
    }

    private static boolean isDeletedBook(BookFileEntity file) {
        return Boolean.TRUE.equals(file.getBook().getDeleted());
    }

    private List<String> decode(BookFileEntity file) {
        try {
            return NestedArchiveLocator.decode(file.getSourceArchiveEntry());
        } catch (RuntimeException _) {
            return List.of(file.getSourceArchiveEntry());
        }
    }

    private boolean startsWith(List<String> chain, List<String> prefix) {
        return chain.size() > prefix.size() && chain.subList(0, prefix.size()).equals(prefix);
    }

    private boolean isOnlyBookFile(BookFileEntity file) {
        Set<BookFileEntity> bookFiles = file.getBook().getBookFiles();
        return bookFiles != null && bookFiles.size() == 1 && bookFiles.iterator().next().equals(file);
    }

    private boolean isHtml(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private boolean isSupport(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && SUPPORT_EXTENSIONS.contains(
                fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    private boolean isImage(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".gif") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    public record ReconciliationResult(int promotedHtml, int retiredAssets) {
    }
}
