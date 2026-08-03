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
    COMPILATION
}
