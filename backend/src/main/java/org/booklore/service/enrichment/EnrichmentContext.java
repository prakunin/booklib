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
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
        replaceIfSet(incoming.getTitle(), existing::setTitle);
        replaceIfSet(incoming.getSubtitle(), existing::setSubtitle);
        replaceIfSet(incoming.getDescription(), existing::setDescription);
        replaceIfSet(incoming.getLanguage(), existing::setLanguage);
        replaceIfSet(incoming.getPublisher(), existing::setPublisher);
        replaceIfSet(incoming.getPublishedDate(), existing::setPublishedDate);
        replaceIfSet(incoming.getSeriesName(), existing::setSeriesName);
        replaceIfSet(incoming.getSeriesNumber(), existing::setSeriesNumber);
        replaceIfSet(incoming.getSeriesTotal(), existing::setSeriesTotal);
        replaceIfSet(incoming.getIsbn10(), existing::setIsbn10);
        replaceIfSet(incoming.getIsbn13(), existing::setIsbn13);
        replaceIfSet(incoming.getAsin(), existing::setAsin);
        replaceIfSet(incoming.getRating(), existing::setRating);
        replaceIfNotEmpty(incoming.getAuthors(), existing::setAuthors);
        replaceIfNotEmpty(incoming.getCategories(), existing::setCategories);
        replaceIfNotEmpty(incoming.getBookReviews(), existing::setBookReviews);
        return existing;
    }

    private static <T> void replaceIfSet(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static <T extends Collection<?>> void replaceIfNotEmpty(T value, Consumer<T> setter) {
        if (value != null && !value.isEmpty()) {
            setter.accept(value);
        }
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
