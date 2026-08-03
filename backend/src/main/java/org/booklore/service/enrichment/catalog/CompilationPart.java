package org.booklore.service.enrichment.catalog;

/**
 * One constituent work of a compilation, identified by the same (archive, entry) key as any other
 * book file. {@code part} is the position the catalog assigns it within the compilation.
 */
public record CompilationPart(String archiveName, String entryName, int part) {
}
