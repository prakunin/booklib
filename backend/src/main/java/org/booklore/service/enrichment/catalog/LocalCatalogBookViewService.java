package org.booklore.service.enrichment.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.LocalCatalogBookViewDto;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.BookMetadataFieldSourceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads everything a library's local catalog holds for one book, for a screen that shows it rather
 * than a pipeline that applies it.
 * <p>
 * Deliberately writes nothing. The catalog is already applied by the enrichment steps under a write
 * policy; this exists for the case those policies leave behind — an annotation the catalog has and
 * the book does not, because a provider filled the description first, or the field is locked, or no
 * run has reached this book. Presenting that difference is the whole job, so the read must not
 * quietly close it.
 * <p>
 * Every lookup is a single indexed row read, so resolving a title per compilation entry is a handful
 * of cheap queries rather than an archive walk. The caps exist for the outliers anyway: an omnibus
 * with hundreds of constituents is a page nobody reads to the end of.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalCatalogBookViewService {

    private static final int MAX_REVIEWS = 50;
    private static final int MAX_COMPILATION_ENTRIES = 100;

    private final BookRepository bookRepository;
    private final LocalCatalogSource catalogSource;
    private final BookMetadataFieldSourceService fieldSourceService;

    public LocalCatalogBookViewDto view(long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        long libraryId = book.getLibrary() == null ? 0L : book.getLibrary().getId();
        BookFileEntity archived = archivedFile(book);
        if (archived == null || libraryId == 0L || !catalogSource.isAvailable(libraryId)) {
            return LocalCatalogBookViewDto.unavailable();
        }

        String archive = archived.getSourceArchive();
        String entry = archived.getSourceArchiveEntry();
        CatalogBookMetadata identity = catalogSource
                .lookupBookMetadata(libraryId, archive, entry)
                .orElse(null);
        List<CatalogReview> reviews = catalogSource.lookupReviews(libraryId, archive, entry);

        return new LocalCatalogBookViewDto(
                true,
                archive,
                entry,
                identity == null ? null : identity.title(),
                identity == null ? List.of() : identity.authors(),
                identity == null ? null : identity.language(),
                catalogSource.lookupDescription(libraryId, archive, entry).orElse(null),
                reviews.size(),
                reviews.stream().limit(MAX_REVIEWS).map(this::toReview).toList(),
                containingCompilations(libraryId, archive, entry),
                compilationParts(libraryId, archive, entry),
                authorBios(libraryId, book, identity),
                catalogFields(bookId));
    }

    private LocalCatalogBookViewDto.Review toReview(CatalogReview review) {
        return new LocalCatalogBookViewDto.Review(review.reviewerName(), review.body(), review.postedAt());
    }

    private List<LocalCatalogBookViewDto.CompilationRef> containingCompilations(
            long libraryId, String archive, String entry) {
        return catalogSource.lookupContainingCompilations(libraryId, archive, entry).stream()
                .limit(MAX_COMPILATION_ENTRIES)
                .map(membership -> toRef(libraryId, membership.compilationArchive(),
                        membership.compilationEntry(), membership.part()))
                .toList();
    }

    private List<LocalCatalogBookViewDto.CompilationRef> compilationParts(
            long libraryId, String archive, String entry) {
        return catalogSource.lookupCompilation(libraryId, archive, entry).stream()
                .limit(MAX_COMPILATION_ENTRIES)
                .map(part -> toRef(libraryId, part.archiveName(), part.entryName(), part.part()))
                .toList();
    }

    private LocalCatalogBookViewDto.CompilationRef toRef(
            long libraryId, String archive, String entry, int part) {
        String title = catalogSource.lookupBookMetadata(libraryId, archive, entry)
                .map(CatalogBookMetadata::title)
                .orElse(null);
        return new LocalCatalogBookViewDto.CompilationRef(archive, entry, part, title);
    }

    /**
     * Both the names the book carries and the names the catalog lists, because they disagree exactly
     * where a biography is most interesting: a book stored under a transliterated or given-name-first
     * spelling misses on its own name and hits on the catalog's.
     */
    private List<LocalCatalogBookViewDto.AuthorBio> authorBios(
            long libraryId, BookEntity book, CatalogBookMetadata identity) {
        Set<String> names = new LinkedHashSet<>();
        BookMetadataEntity metadata = book.getMetadata();
        if (metadata != null && metadata.getAuthors() != null) {
            metadata.getAuthors().stream()
                    .map(AuthorEntity::getName)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .forEach(names::add);
        }
        if (identity != null) {
            names.addAll(identity.authors());
        }
        return names.stream()
                .map(name -> catalogSource.lookupAuthorBio(libraryId, name)
                        .map(bio -> new LocalCatalogBookViewDto.AuthorBio(name, bio))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Sorted rather than in whatever order the provenance query returned it: this set is rendered as
     * a list of field names and a stable one reads as a fact instead of as noise that moves between
     * refreshes.
     */
    private Set<MetadataField> catalogFields(long bookId) {
        Map<MetadataField, MetadataProvider> sources = fieldSourceService.sourcesForBook(bookId);
        Set<MetadataField> fields = new TreeSet<>();
        sources.forEach((field, provider) -> {
            if (provider == MetadataProvider.FlibustaLocal) {
                fields.add(field);
            }
        });
        return fields;
    }

    /**
     * The file the catalog can be keyed on. A book outside an archive simply has none — the same rule
     * the enrichment pipeline applies, so the screen shows a catalog entry exactly when a run would
     * have found one.
     */
    private BookFileEntity archivedFile(BookEntity book) {
        if (book.getBookFiles() == null) {
            return null;
        }
        return book.getBookFiles().stream()
                .filter(file -> file.getSourceArchive() != null && file.getSourceArchiveEntry() != null)
                .findFirst()
                .orElse(null);
    }
}
