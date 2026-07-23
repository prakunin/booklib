package org.booklore.service.event;

/**
 * Published after a library scan or import completes for {@code libraryId}. Lets listeners refresh
 * per-library derived data (e.g. materialized statistics) off the request path once the catalog has
 * settled, so those aggregates are recomputed at import time instead of waiting for the hourly sweep.
 */
public record LibraryScanCompletedEvent(Long libraryId) {
}
