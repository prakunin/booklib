package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.LocalCatalogSourceType;

/**
 * Where in a local catalog a given key lives.
 * <p>
 * Reviews are spread over 229 monthly archives and author biographies over 79 buckets, and neither
 * container can be derived from the key — so the containers are scanned once and the mapping is kept
 * here rather than re-derived on every lookup.
 */
@Entity
@Table(name = "local_catalog_index")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalCatalogIndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false)
    private Long libraryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private LocalCatalogSourceType sourceType;

    @Column(name = "entry_key", nullable = false, length = 320)
    private String entryKey;

    /** The archive within the catalog that holds the key, or null when the payload is inline. */
    @Column(name = "container", length = 255)
    private String container;

    /** Inline value for keys whose data is small enough not to warrant a second read. */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;
}
