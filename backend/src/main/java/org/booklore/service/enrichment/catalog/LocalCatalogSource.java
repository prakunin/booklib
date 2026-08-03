package org.booklore.service.enrichment.catalog;

import java.util.List;
import java.util.Optional;

/**
 * A catalog of book metadata that ships alongside a library as plain files, joined on the exact keys
 * the library already stores — the source archive and the entry name inside it.
 * <p>
 * This is the cheapest source the enrichment pipeline has: no network, no scraping, no agent, and an
 * exact-key match rather than a title guess. Implementations are per-layout;
 * {@link FlibustaCatalogSource} is the layout that ships with the fb2.Flibusta.Net INPX libraries.
 */
public interface LocalCatalogSource {

    /**
     * Whether this source can serve lookups for the given library at all — the path is configured,
     * present, and readable. Callers use it to skip the step rather than to handle failures.
     */
    boolean isAvailable(long libraryId);

    /**
     * The catalog's annotation for a book file — the description the pipeline proposes.
     */
    Optional<String> lookupDescription(long libraryId, String archiveName, String entryName);

    List<CatalogReview> lookupReviews(long libraryId, String archiveName, String entryName);

    /**
     * @param authorName the author as the library stores it, e.g. {@code "Хэндлер Дэниел"}
     */
    Optional<String> lookupAuthorBio(long libraryId, String authorName);

    List<CompilationPart> lookupCompilation(long libraryId, String archiveName, String entryName);

    /**
     * The language the catalog lists this book under, as a lower-case code such as {@code "ru"}.
     */
    default Optional<String> lookupLanguage(long libraryId, String archiveName, String entryName) {
        return Optional.empty();
    }

    /**
     * The omnibus this book is a part of, when the catalog says it is one.
     */
    default Optional<CompilationMembership> lookupContainingCompilation(
            long libraryId, String archiveName, String entryName) {
        return Optional.empty();
    }
}
