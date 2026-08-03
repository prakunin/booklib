package org.booklore.service.enrichment;

import lombok.Getter;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.EnrichmentStepType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.enrichment.catalog.CatalogReview;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one book's enrichment run accumulates as it moves through the steps.
 * <p>
 * The book is a detached DTO on purpose: the steps that cost real time — provider scrapes, the agent
 * — run outside any transaction, and touching a lazy association there would fail long after the
 * session that could have loaded it has closed.
 */
@Getter
public class EnrichmentContext {

    private final Book book;
    private final long libraryId;
    private final String sourceArchive;
    private final String sourceArchiveEntry;
    private final EnrichmentRequest request;
    private final Set<EnrichmentStepType> allowedSteps;

    private final Map<MetadataProvider, BookMetadata> contributions = new LinkedHashMap<>();
    private final Map<MetadataProvider, EnrichmentConfidence> confidences = new EnumMap<>(MetadataProvider.class);
    private final List<CatalogReview> catalogReviews = new ArrayList<>();
    private final Map<String, String> authorBios = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final Set<EnrichmentStepType> stepsRun = EnumSet.noneOf(EnrichmentStepType.class);

    public EnrichmentContext(Book book, long libraryId, String sourceArchive, String sourceArchiveEntry,
                             EnrichmentRequest request) {
        this.book = book;
        this.libraryId = libraryId;
        this.sourceArchive = sourceArchive;
        this.sourceArchiveEntry = sourceArchiveEntry;
        this.request = request;
        this.allowedSteps = request.resolvedSteps();
    }

    public boolean isStepAllowed(EnrichmentStepType step) {
        return allowedSteps.contains(step);
    }

    public BookMetadata existingMetadata() {
        return book.getMetadata();
    }

    public long bookId() {
        return book.getId();
    }

    /**
     * Records what a source found. A step that finds nothing must not call this — an empty
     * contribution would make the provider look like a candidate for every field it left null.
     * <p>
     * Repeated calls for one provider join rather than replace. Several steps legitimately speak for
     * the same source — the local catalog supplies a description, a language and a compilation
     * series from three different files — and a plain {@code put} would let the last of them discard
     * the others.
     */
    public void addContribution(MetadataProvider provider, BookMetadata metadata, EnrichmentConfidence confidence) {
        if (metadata == null) {
            return;
        }
        BookMetadata existing = contributions.get(provider);
        contributions.put(provider, existing == null ? metadata : merged(existing, metadata));
        confidences.merge(provider, confidence, (current, incoming) ->
                current.isAtLeast(incoming) ? current : incoming);
    }

    /**
     * Field-wise, incoming-wins-where-set. Only the fields enrichment steps actually contribute are
     * listed; a field absent here is a field no step produces, and adding one means adding it here.
     */
    private BookMetadata merged(BookMetadata existing, BookMetadata incoming) {
        if (incoming.getTitle() != null) existing.setTitle(incoming.getTitle());
        if (incoming.getSubtitle() != null) existing.setSubtitle(incoming.getSubtitle());
        if (incoming.getDescription() != null) existing.setDescription(incoming.getDescription());
        if (incoming.getLanguage() != null) existing.setLanguage(incoming.getLanguage());
        if (incoming.getPublisher() != null) existing.setPublisher(incoming.getPublisher());
        if (incoming.getPublishedDate() != null) existing.setPublishedDate(incoming.getPublishedDate());
        if (incoming.getSeriesName() != null) existing.setSeriesName(incoming.getSeriesName());
        if (incoming.getSeriesNumber() != null) existing.setSeriesNumber(incoming.getSeriesNumber());
        if (incoming.getSeriesTotal() != null) existing.setSeriesTotal(incoming.getSeriesTotal());
        if (incoming.getIsbn10() != null) existing.setIsbn10(incoming.getIsbn10());
        if (incoming.getIsbn13() != null) existing.setIsbn13(incoming.getIsbn13());
        if (incoming.getAsin() != null) existing.setAsin(incoming.getAsin());
        if (incoming.getRating() != null) existing.setRating(incoming.getRating());
        if (incoming.getAuthors() != null && !incoming.getAuthors().isEmpty()) {
            existing.setAuthors(incoming.getAuthors());
        }
        if (incoming.getCategories() != null && !incoming.getCategories().isEmpty()) {
            existing.setCategories(incoming.getCategories());
        }
        if (incoming.getBookReviews() != null && !incoming.getBookReviews().isEmpty()) {
            existing.setBookReviews(incoming.getBookReviews());
        }
        return existing;
    }

    public void addCatalogReviews(List<CatalogReview> reviews) {
        catalogReviews.addAll(reviews);
    }

    public void addAuthorBio(String authorName, String bio) {
        authorBios.put(authorName, bio);
    }

    public void note(String note) {
        notes.add(note);
    }

    public void markStepRun(EnrichmentStepType step) {
        stepsRun.add(step);
    }

    /**
     * Whether anything has already established which book this is. The agent step is the expensive
     * one and exists only to answer this question, so it runs only when the answer is still no.
     */
    public boolean hasIdentifier() {
        if (identifierOn(existingMetadata())) {
            return true;
        }
        return contributions.values().stream().anyMatch(this::identifierOn);
    }

    private boolean identifierOn(BookMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return isPresent(metadata.getIsbn13())
                || isPresent(metadata.getIsbn10())
                || isPresent(metadata.getAsin())
                || isPresent(metadata.getGoodreadsId());
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
