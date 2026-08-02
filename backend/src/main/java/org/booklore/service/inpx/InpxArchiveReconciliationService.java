package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
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

@Service
@RequiredArgsConstructor
public class InpxArchiveReconciliationService {

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
}
