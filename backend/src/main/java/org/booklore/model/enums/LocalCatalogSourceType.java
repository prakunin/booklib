package org.booklore.model.enums;

/**
 * What a {@code local_catalog_index} row points at. Annotations are deliberately absent: they are
 * keyed by the archive name, which is already the entry name inside their own container, so they
 * need no reverse index.
 */
public enum LocalCatalogSourceType {
    /** Key is {@code <archive>.zip#<entry>.fb2}, container is the monthly reviews archive. */
    REVIEW,
    /** Key is the MD5 of the normalized author name, container is the bucket archive. */
    AUTHOR_BIO,
    /** Key is {@code <archive>.zip#<entry>.fb2}, payload holds the parts as JSON. */
    COMPILATION,
    /**
     * Metadata from one {@code contents.7z} row, keyed by archive and entry. Payload holds JSON with
     * title, authors and the language derived from the listing filename. The enum name stays
     * {@code LANGUAGE} for compatibility with already persisted rows.
     */
    LANGUAGE,
    /**
     * The reverse of {@link #COMPILATION}: filed under a constituent work's key, naming the omnibus
     * it belongs to and its position. {@code COMPILATION} answers "what is in this omnibus";
     * this answers "which omnibus is this part of", which is what setting a series needs.
     */
    COMPILATION_PART,
    /**
     * One completion marker per library. Its presence means the index was built by a version that
     * stores book identity in the contents rows and that every index pass reached the end.
     */
    INDEX_VERSION
}
