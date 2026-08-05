package org.booklore.service.metadata;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.mapper.BookMetadataMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.BookMetadataFieldSourceEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataFieldSourceRepository;
import org.booklore.repository.BookMetadataRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Carries per-field provenance across the review-gated metadata path.
 * <p>
 * On the direct paths the merger's provider map reaches {@code BookMetadataUpdater} in the same call
 * that writes the values, and the rows are filed there. Under {@code reviewBeforeApply} the merged
 * result is parked in a proposal and applied later by the <em>client</em>, which replays it through the
 * ordinary metadata PUT — a request indistinguishable at the server from a user typing into the editor,
 * and one that carries no providers because {@code fieldProviders} is {@code @JsonIgnore}. Without this
 * service that path records nothing, and worse, the accept deletes the rows of every field it changes.
 * <p>
 * Two halves, and the split is what keeps the rule intact:
 * <ul>
 *   <li>{@link #describeChanges} runs when the proposal is built, the only moment at which both the
 *       proposed value and the value the book already held are in hand. It stores <em>only</em> the
 *       fields the proposal would actually change. That is the direct path's {@code WRITTEN} rule: a
 *       provider that merely agreed with a value already present earns no attribution, because
 *       agreement cannot be told apart from the user having typed what the provider would have said.</li>
 *   <li>{@link #recordAcceptedProposal} runs on ACCEPT and files a row only where the value now stored
 *       is the one the provider proposed. Anything the user edited in the picker before saving differs,
 *       and is silently dropped.</li>
 * </ul>
 * The composition can only ever under-attribute — if the accept never arrives, or the values were
 * edited, or the map was never stored, the outcome is an absent row, which the schema already defines
 * as "origin not recorded". It can never invent a provider for something a user wrote.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataProposalProvenanceService {

    private static final TypeReference<Map<MetadataField, MetadataProvider>> PROVIDER_MAP =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final BookMetadataRepository bookMetadataRepository;
    private final BookMetadataMapper bookMetadataMapper;
    private final BookMetadataFieldSourceRepository fieldSourceRepository;

    /**
     * The serialised provider map to store on a proposal, or null when there is nothing worth storing.
     *
     * @param proposed the merged metadata the proposal offers
     * @param existing what the book held when the proposal was built
     */
    public String describeChanges(BookMetadata proposed, BookMetadata existing) {
        if (proposed == null || proposed.getFieldProviders() == null || proposed.getFieldProviders().isEmpty()) {
            return null;
        }
        if (existing == null) {
            // Not "the book was empty" but "the previous state could not be read". Attributing every
            // field on that basis would invent provenance; storing none only loses some.
            return null;
        }
        Map<MetadataField, MetadataProvider> changed = new EnumMap<>(MetadataField.class);
        proposed.getFieldProviders().forEach((field, provider) -> {
            if (provider == null) {
                return;
            }
            Object proposedValue = MetadataFieldAccessors.valueOf(field, proposed);
            Object existingValue = MetadataFieldAccessors.valueOf(field, existing);
            if (proposedValue != null && !Objects.equals(proposedValue, existingValue)) {
                changed.put(field, provider);
            }
        });
        if (changed.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(changed);
        } catch (Exception e) {
            log.warn("Could not record the provider map for a metadata proposal: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Files the provenance rows for a proposal the user has just accepted.
     * <p>
     * Runs after the client's PUT has already stored the accepted values, so "the value now stored" is
     * the accepted one. Must be called inside the caller's transaction — the proposal's JSON columns
     * are lazy.
     */
    public void recordAcceptedProposal(MetadataFetchProposalEntity proposal) {
        if (proposal == null || proposal.getBookId() == null) {
            return;
        }
        Map<MetadataField, MetadataProvider> providers = readProviders(proposal);
        if (providers.isEmpty()) {
            return;
        }
        BookMetadata proposed = readProposedMetadata(proposal);
        if (proposed == null) {
            return;
        }
        BookMetadataEntity metadataEntity = bookMetadataRepository.findById(proposal.getBookId()).orElse(null);
        if (metadataEntity == null) {
            return;
        }
        BookMetadata stored = bookMetadataMapper.toBookMetadata(metadataEntity, true);

        Instant now = Instant.now();
        List<BookMetadataFieldSourceEntity> rows = new ArrayList<>();
        providers.forEach((field, provider) -> {
            Object proposedValue = MetadataFieldAccessors.valueOf(field, proposed);
            Object storedValue = MetadataFieldAccessors.valueOf(field, stored);
            if (proposedValue != null && Objects.equals(proposedValue, storedValue)) {
                rows.add(BookMetadataFieldSourceEntity.builder()
                        .bookId(proposal.getBookId())
                        .fieldName(field)
                        .provider(provider)
                        .updatedAt(now)
                        .build());
            }
        });
        if (!rows.isEmpty()) {
            fieldSourceRepository.saveAll(rows);
        }
    }

    private Map<MetadataField, MetadataProvider> readProviders(MetadataFetchProposalEntity proposal) {
        String json = proposal.getFieldProvidersJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<MetadataField, MetadataProvider> providers = objectMapper.readValue(json, PROVIDER_MAP);
            return providers == null ? Map.of() : providers;
        } catch (Exception e) {
            log.warn("Could not read the provider map of proposal {}: {}", proposal.getProposalId(), e.getMessage());
            return Map.of();
        }
    }

    private BookMetadata readProposedMetadata(MetadataFetchProposalEntity proposal) {
        String json = proposal.getMetadataJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, BookMetadata.class);
        } catch (Exception e) {
            log.warn("Could not read the metadata of proposal {}: {}", proposal.getProposalId(), e.getMessage());
            return null;
        }
    }
}
