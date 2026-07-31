package org.booklore.model.dto.response;

/**
 * Whether a DjVu book's searchable PDF rendition can be opened yet.
 *
 * @param ready true when the rendition exists for the source file as it currently stands
 */
public record DjvuRenditionStatus(boolean ready) {
}
