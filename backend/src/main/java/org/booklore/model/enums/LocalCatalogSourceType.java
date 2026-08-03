package org.booklore.model.enums;

/**
 * What a {@code local_catalog_index} row points at. Annotations and contents are deliberately absent:
 * they are keyed by the archive name, which is already the entry name inside their own container, so
 * they need no reverse index.
 */
public enum LocalCatalogSourceType {
    /** Key is {@code <archive>.zip#<entry>.fb2}, container is the monthly reviews archive. */
    REVIEW,
    /** Key is the MD5 of the normalized author name, container is the bucket archive. */
    AUTHOR_BIO,
    /** Key is {@code <archive>.zip#<entry>.fb2}, payload holds the parts as JSON. */
    COMPILATION,
    /**
     * The language a book is listed under in {@code contents.7z}. Payload is the language code taken
     * from the listing's file name; there is no language column in the rows themselves.
     */
    LANGUAGE,
    /**
     * The reverse of {@link #COMPILATION}: filed under a constituent work's key, naming the omnibus
     * it belongs to and its position. {@code COMPILATION} answers "what is in this omnibus";
     * this answers "which omnibus is this part of", which is what setting a series needs.
     */
    COMPILATION_PART
}
