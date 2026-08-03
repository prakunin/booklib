package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.EnrichmentConfidence;
import org.booklore.model.enums.WorkIdentitySource;

import java.time.Instant;

/**
 * A literary work, resolved once and shared by every edition of it.
 *
 * @see org.booklore.service.enrichment.work.WorkIdentityService
 */
@Entity
@Table(name = "work_identity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkIdentityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalized {@code author|title}; see {@code WorkKeys}. */
    @Column(name = "work_key", nullable = false, length = 512)
    private String workKey;

    @Column(name = "original_title", length = 1000)
    private String originalTitle;

    @Column(name = "original_author", length = 512)
    private String originalAuthor;

    @Column(name = "original_language", length = 32)
    private String originalLanguage;

    @Column(name = "goodreads_id", length = 64)
    private String goodreadsId;

    @Column(name = "isbn13", length = 20)
    private String isbn13;

    @Column(name = "isbn10", length = 20)
    private String isbn10;

    @Column(name = "first_published_year")
    private Integer firstPublishedYear;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_language", length = 32)
    private String descriptionLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 16)
    private EnrichmentConfidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_by", nullable = false, length = 16)
    private WorkIdentitySource resolvedBy;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;
}
