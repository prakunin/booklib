package org.booklore.service.enrichment.catalog;

/**
 * The omnibus a constituent work belongs to, and its position within it.
 */
public record CompilationMembership(String compilationArchive, String compilationEntry, int part) {
}
