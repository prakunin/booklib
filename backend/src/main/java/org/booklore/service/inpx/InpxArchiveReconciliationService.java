package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
public class InpxArchiveReconciliationService {

    private static final Set<String> SUPPORT_EXTENSIONS = Set.of(
            "css", "js", "gif", "jpg", "jpeg", "png", "webp", "svg", "xml", "xsl", "xslt",
            "woff", "woff2", "ttf", "otf", "eot");

    private final BookFileRepository bookFileRepository;
    private final BookRepository bookRepository;
    private final ArchiveEntryMetadataRecognizer entryMetadataRecognizer;

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
        int promotedHtml = 0;
        Set<List<String>> htmlParents = new HashSet<>();
        Set<List<String>> comicContainers = new HashSet<>();

        for (BookFileEntity file : files) {
            if (Boolean.TRUE.equals(file.getBook().getDeleted())) {
                continue;
            }
            List<String> chain = decode(file);
            if (isHtml(file.getFileName()) && chain.size() >= 2) {
                htmlParents.add(List.copyOf(chain.subList(0, chain.size() - 1)));
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

        for (BookFileEntity container : files) {
            if (Boolean.TRUE.equals(container.getBook().getDeleted())
                    || container.getBookType() != BookFileType.OTHER
                    || !entryMetadataRecognizer.isGenericArchive(container.getFileName())) {
                continue;
            }
            List<String> containerChain = decode(container);
            List<BookFileEntity> descendants = files.stream()
                    .filter(file -> !Boolean.TRUE.equals(file.getBook().getDeleted()))
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

        Map<Long, BookEntity> booksToRetire = new LinkedHashMap<>();
        for (BookFileEntity file : files) {
            if (Boolean.TRUE.equals(file.getBook().getDeleted()) || !isSupport(file.getFileName())) {
                continue;
            }
            List<String> chain = decode(file);
            if (chain.size() < 2) {
                continue;
            }
            List<String> parent = List.copyOf(chain.subList(0, chain.size() - 1));
            boolean htmlAsset = htmlParents.contains(parent);
            boolean comicAsset = comicContainers.stream().anyMatch(container -> startsWith(chain, container));
            if ((htmlAsset || comicAsset) && isOnlyBookFile(file)) {
                booksToRetire.put(file.getBook().getId(), file.getBook());
            }
        }

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
        List<BookFileEntity> bookFiles = file.getBook().getBookFiles();
        return bookFiles != null && bookFiles.size() == 1 && bookFiles.getFirst().equals(file);
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
