package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.EnrichmentConfidence;

import java.time.Instant;

/**
 * Ties one book to the work it is an edition of, recording how sure that tie was — a link made on a
 * matching ISBN is a different claim from one made on a normalized author and title alone.
 */
@Entity
@Table(name = "book_work_link")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookWorkLinkEntity {

    @Id
    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "work_identity_id", nullable = false)
    private Long workIdentityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_confidence", nullable = false, length = 16)
    private EnrichmentConfidence matchConfidence;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;
}
