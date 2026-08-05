package org.booklore.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.booklore.model.enums.MetadataField;
import org.booklore.model.enums.MetadataProvider;

import java.io.Serializable;
import java.time.Instant;

/**
 * One field of one book's metadata, and the provider whose value is currently stored in it.
 * <p>
 * The row is an assertion about what {@code book_metadata} holds right now, not a history: there is
 * at most one per (book, field), and the absence of a row means the field's origin is unknown rather
 * than that a human typed it.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "book_metadata_field_source")
@IdClass(BookMetadataFieldSourceEntity.FieldSourceId.class)
public class BookMetadataFieldSourceEntity {

    @Id
    @Column(name = "book_id")
    private Long bookId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "field_name", length = 64)
    private MetadataField fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 64)
    private MetadataProvider provider;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldSourceId implements Serializable {
        private Long bookId;
        private MetadataField fieldName;
    }
}
