package org.booklore.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.MetadataReplaceMode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataUpdateContext {
    private BookEntity bookEntity;
    private MetadataUpdateWrapper metadataUpdateWrapper;
    private boolean updateThumbnail;
    private boolean mergeCategories;
    /** Null keeps the legacy behavior where author merging follows mergeCategories. */
    private Boolean mergeAuthors;
    private boolean mergeMoods;
    private boolean mergeTags;
    private MetadataReplaceMode replaceMode;
}
