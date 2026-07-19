package org.booklore.model.enums;

@SuppressWarnings("java:S115") // constant names are JSON values sent by clients in merge/delete metadata requests
public enum MergeMetadataType {
    authors,
    categories,
    moods,
    tags,
    series,
    publishers,
    languages
}
