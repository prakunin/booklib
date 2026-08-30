package org.booklore.model.dto.inpx;

import org.booklore.model.enums.MetadataField;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What a library's local catalog holds for one book, read from the catalog itself rather than from
 * what enrichment has already written into the book.
 * <p>
 * The point of the distinction is that the two differ, and the difference is what the screen exists
 * to show: a write policy of {@code AUTO_IF_EMPTY} leaves the catalog's annotation unused when a
 * provider already filled the description, a locked field is never overwritten at all, and a run may
 * simply not have reached this book yet. {@link #fieldsFromCatalog} is the other half of that
 * answer — the fields whose recorded provenance is the catalog, so a reader can tell "the catalog
 * has this" from "the catalog gave us this".
 *
 * @param available whether this book can be looked up at all: it has a file that came out of an
 *                  archive, and its library points at a readable catalog. Everything else is empty
 *                  when this is false
 * @param sourceArchive the archive the book's file came from — the first half of the catalog key
 * @param sourceArchiveEntry the entry name inside that archive — the second half
 * @param reviewCount the total the catalog holds, which may exceed the number in {@link #reviews}
 * @param reviews a capped prefix of the reviews, oldest first
 * @param containingCompilations the omnibuses this book is a constituent of
 * @param compilationParts the constituent works of this book, when it is itself an omnibus
 * @param fieldsFromCatalog fields on this book whose stored provenance is the local catalog
 */
public record LocalCatalogBookViewDto(
        boolean available,
        String sourceArchive,
        String sourceArchiveEntry,
        String title,
        List<String> authors,
        String language,
        String description,
        int reviewCount,
        List<Review> reviews,
        List<CompilationRef> containingCompilations,
        List<CompilationRef> compilationParts,
        List<AuthorBio> authorBios,
        Set<MetadataField> fieldsFromCatalog) {

    /**
     * The answer for a book the catalog cannot be keyed on. Every collection is empty rather than
     * null so the caller never has to tell "no catalog" apart from "catalog with nothing in it" by
     * inspecting for nulls — {@link #available} is that answer, and it is the only one to read.
     */
    public static LocalCatalogBookViewDto unavailable() {
        return new LocalCatalogBookViewDto(false, null, null, null, List.of(), null, null,
                0, List.of(), List.of(), List.of(), List.of(), Set.of());
    }

    /**
     * @param reviewerName frequently blank in the source data, hence nullable
     */
    public record Review(String reviewerName, String body, Instant postedAt) {
    }

    /**
     * One end of a compilation relationship.
     *
     * @param title the compiled work's title as the catalog lists it, or {@code null} when the
     *              catalog holds the relationship but no listing row for that key
     */
    public record CompilationRef(String archiveName, String entryName, int part, String title) {
    }

    public record AuthorBio(String authorName, String biography) {
    }
}
