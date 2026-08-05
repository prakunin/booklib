package org.booklore.service.enrichment.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.inpx.LocalCatalogStatusDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.BookReviewRepository;
import org.booklore.repository.LocalCatalogIndexRepository;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Read-only aggregator that reports what a library's local catalog has indexed and how much of the
 * library's metadata the backfill has actually filled.
 * <p>
 * This holds no state, decodes nothing, and opens no archive — it only reads
 * {@link LocalCatalogIndexRepository}, built by {@link LocalCatalogIndexBuilder}, which is already the
 * answer to "what does the catalog hold".
 * <p>
 * Takes an already-resolved {@link LibraryEntity} rather than a bare id: whether the library exists and
 * is eligible at all (an INPX library, in {@code InpxController}'s case) is a validation concern that
 * belongs to the caller, not to an aggregator in the {@code enrichment} package — this class must not
 * depend on {@code service.inpx}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCatalogStatusService {

    private final LocalCatalogIndexRepository localCatalogIndexRepository;
    private final BookRepository bookRepository;
    private final BookReviewRepository bookReviewRepository;
    private final AuthorRepository authorRepository;

    public LocalCatalogStatusDto getStatus(LibraryEntity library) {
        long libraryId = library.getId();
        String catalogPath = library.getMetadataSidecarPath();
        boolean configured = catalogPath != null && !catalogPath.isBlank();

        Map<LocalCatalogSourceType, Long> indexedEntries = new EnumMap<>(LocalCatalogSourceType.class);
        for (LocalCatalogSourceType sourceType : LocalCatalogSourceType.values()) {
            indexedEntries.put(sourceType,
                    localCatalogIndexRepository.countByLibraryIdAndSourceType(libraryId, sourceType));
        }

        return new LocalCatalogStatusDto(
                configured,
                catalogPath,
                indexedEntries,
                bookRepository.countByLibraryIdNonDeleted(libraryId),
                bookRepository.countByLibraryIdNonDeletedWithDescription(libraryId),
                bookReviewRepository.countByMetadataProviderAndBookMetadataBookLibraryId(
                        MetadataProvider.FlibustaLocal, libraryId),
                authorRepository.countWithNonBlankDescription());
    }
}
