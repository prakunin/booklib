package org.booklore.task.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for {@link org.booklore.model.enums.TaskType#LOCAL_CATALOG_BACKFILL}. The backfill is
 * per-library by design (see {@code LocalCatalogBackfillTask}), so a library id is the only option
 * it needs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalCatalogBackfillOptions {

    private Long libraryId;
}
