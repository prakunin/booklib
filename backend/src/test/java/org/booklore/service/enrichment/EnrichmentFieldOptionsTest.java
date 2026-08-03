package org.booklore.service.enrichment;

import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.enums.MetadataProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentFieldOptionsTest {

    private final EnrichmentFieldOptions fieldOptions = new EnrichmentFieldOptions(new ObjectMapper());

    private MetadataRefreshOptions optionsWithDescriptionChain(MetadataProvider... providers) {
        MetadataRefreshOptions.FieldProvider.FieldProviderBuilder chain = MetadataRefreshOptions.FieldProvider.builder();
        if (providers.length > 0) chain.p1(providers[0]);
        if (providers.length > 1) chain.p2(providers[1]);
        if (providers.length > 2) chain.p3(providers[2]);
        if (providers.length > 3) chain.p4(providers[3]);
        return MetadataRefreshOptions.builder()
                .fieldOptions(MetadataRefreshOptions.FieldOptions.builder().description(chain.build()).build())
                .build();
    }

    @Test
    void ranksTheLocalCatalogInTheFirstFreeSlot() {
        MetadataRefreshOptions result = fieldOptions.withLocalCatalog(
                optionsWithDescriptionChain(MetadataProvider.Amazon));

        MetadataRefreshOptions.FieldProvider chain = result.getFieldOptions().getDescription();
        assertThat(chain.getP1()).isEqualTo(MetadataProvider.Amazon);
        assertThat(chain.getP2()).isEqualTo(MetadataProvider.FlibustaLocal);
    }

    @Test
    void ranksItFirstWhenNothingIsConfigured() {
        MetadataRefreshOptions result = fieldOptions.withLocalCatalog(optionsWithDescriptionChain());

        assertThat(result.getFieldOptions().getDescription().getP1()).isEqualTo(MetadataProvider.FlibustaLocal);
    }

    /**
     * A chain the user has filled with four providers is a decision, not an oversight.
     */
    @Test
    void neverDisplacesAConfiguredProvider() {
        MetadataRefreshOptions result = fieldOptions.withLocalCatalog(optionsWithDescriptionChain(
                MetadataProvider.Amazon, MetadataProvider.GoodReads,
                MetadataProvider.Google, MetadataProvider.Hardcover));

        MetadataRefreshOptions.FieldProvider chain = result.getFieldOptions().getDescription();
        assertThat(chain.getP1()).isEqualTo(MetadataProvider.Amazon);
        assertThat(chain.getP4()).isEqualTo(MetadataProvider.Hardcover);
    }

    @Test
    void doesNotAddItTwice() {
        MetadataRefreshOptions once = fieldOptions.withLocalCatalog(optionsWithDescriptionChain(MetadataProvider.Amazon));
        MetadataRefreshOptions twice = fieldOptions.withLocalCatalog(once);

        MetadataRefreshOptions.FieldProvider chain = twice.getFieldOptions().getDescription();
        assertThat(chain.getP2()).isEqualTo(MetadataProvider.FlibustaLocal);
        assertThat(chain.getP3()).isNull();
    }

    /**
     * The argument comes from the shared application settings; mutating it would change how every
     * other metadata refresh in the instance behaves.
     */
    @Test
    void leavesTheOriginalOptionsUntouched() {
        MetadataRefreshOptions original = optionsWithDescriptionChain(MetadataProvider.Amazon);

        fieldOptions.withLocalCatalog(original);

        assertThat(original.getFieldOptions().getDescription().getP2()).isNull();
    }

    @Test
    void toleratesOptionsWithoutFieldOptions() {
        MetadataRefreshOptions result = fieldOptions.withLocalCatalog(MetadataRefreshOptions.builder().build());

        assertThat(result.getFieldOptions().getDescription().getP1()).isEqualTo(MetadataProvider.FlibustaLocal);
    }
}
