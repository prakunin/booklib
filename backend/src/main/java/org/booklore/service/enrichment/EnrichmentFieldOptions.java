package org.booklore.service.enrichment;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.enums.MetadataProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Makes the pipeline-only providers visible to the per-field priority table.
 * <p>
 * {@code MetadataMerger} only ever considers a provider that appears in a field's p1–p4 chain, so a
 * contribution from the local catalog or the agent would be silently ignored unless it is ranked.
 * They are appended into the first free slot and never displace a configured provider: a chain the
 * user has filled with four providers is a decision, not an oversight.
 * <p>
 * Placement matters less than it appears. Which value is <em>written</em> is decided by confidence,
 * not by rank — a scraped description that outranks the local catalog still only becomes a
 * suggestion when nothing corroborates it, while the exact-key local match is written.
 */
@Component
@RequiredArgsConstructor
public class EnrichmentFieldOptions {

    /**
     * Fields the local catalog can supply: annotations (descriptions), per-language listings,
     * and compilation membership (series).
     */
    private static final List<FieldSlot> LOCAL_CATALOG_FIELDS = List.of(
            new FieldSlot(MetadataRefreshOptions.FieldOptions::getDescription,
                    MetadataRefreshOptions.FieldOptions::setDescription),
            new FieldSlot(MetadataRefreshOptions.FieldOptions::getLanguage,
                    MetadataRefreshOptions.FieldOptions::setLanguage),
            new FieldSlot(MetadataRefreshOptions.FieldOptions::getSeriesName,
                    MetadataRefreshOptions.FieldOptions::setSeriesName),
            new FieldSlot(MetadataRefreshOptions.FieldOptions::getSeriesNumber,
                    MetadataRefreshOptions.FieldOptions::setSeriesNumber));

    private final ObjectMapper objectMapper;

    /**
     * @return a copy of the options with the local catalog ranked; the argument is never modified,
     * because it comes from the shared application settings and mutating it would change how every
     * other metadata refresh in the instance behaves
     */
    public MetadataRefreshOptions withLocalCatalog(MetadataRefreshOptions options) {
        if (options == null) {
            return null;
        }
        MetadataRefreshOptions copy = objectMapper.convertValue(options, MetadataRefreshOptions.class);
        MetadataRefreshOptions.FieldOptions fieldOptions = copy.getFieldOptions();
        if (fieldOptions == null) {
            fieldOptions = new MetadataRefreshOptions.FieldOptions();
            copy.setFieldOptions(fieldOptions);
        }
        for (FieldSlot field : LOCAL_CATALOG_FIELDS) {
            MetadataRefreshOptions.FieldProvider chain = field.getter().apply(fieldOptions);
            if (chain == null) {
                chain = MetadataRefreshOptions.FieldProvider.builder().build();
                field.setter().accept(fieldOptions, chain);
            }
            appendIfAbsent(chain, MetadataProvider.FlibustaLocal);
        }
        return copy;
    }

    private void appendIfAbsent(MetadataRefreshOptions.FieldProvider chain, MetadataProvider provider) {
        if (provider == chain.getP1() || provider == chain.getP2()
                || provider == chain.getP3() || provider == chain.getP4()) {
            return;
        }
        if (chain.getP1() == null) {
            chain.setP1(provider);
        } else if (chain.getP2() == null) {
            chain.setP2(provider);
        } else if (chain.getP3() == null) {
            chain.setP3(provider);
        } else if (chain.getP4() == null) {
            chain.setP4(provider);
        }
    }

    private record FieldSlot(
            Function<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider> getter,
            BiConsumer<MetadataRefreshOptions.FieldOptions, MetadataRefreshOptions.FieldProvider> setter) {
    }
}
