package org.booklore.service.enrichment.catalog;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * The metadata carried inline by one {@code contents.7z} index row.
 *
 * @param title book title from the listing
 * @param authors display-order author names decoded from the INPX-style author field
 * @param language language derived from the listing filename
 */
public record CatalogBookMetadata(String title, List<String> authors, String language) {

    public CatalogBookMetadata {
        title = StringUtils.trimToNull(title);
        authors = authors == null ? List.of() : authors.stream()
                .map(StringUtils::trimToNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        language = StringUtils.trimToNull(language);
    }
}
