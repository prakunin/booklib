package org.booklore.model.entity;

import org.booklore.model.enums.FetchedMetadataProposalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.LazyGroup;

import java.time.Instant;

@Entity
@Table(name = "metadata_fetch_proposals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataFetchProposalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "proposal_id")
    private Long proposalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private MetadataFetchJobEntity job;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewer_user_id")
    private Long reviewerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FetchedMetadataProposalStatus status;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @LazyGroup("lob")
    @Column(name = "metadata_json", columnDefinition = "JSON")
    private String metadataJson;

    /**
     * Which provider supplied each field this proposal would actually change, kept so the accept can
     * file provenance rows the client-replayed PUT cannot carry. Server-side only: no DTO or mapper
     * exposes it, so it can neither be read nor asserted by a client.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @LazyGroup("lob")
    @Column(name = "field_providers_json", columnDefinition = "JSON")
    private String fieldProvidersJson;
}
