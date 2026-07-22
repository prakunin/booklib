package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.util.AuthorNames;
import org.booklore.util.AuthorSortName;
import org.hibernate.Hibernate;
import org.hibernate.annotations.BatchSize;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "author")
public class AuthorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "sort_name")
    private String sortName;

    @Column(name = "sort_name_locked", nullable = false)
    private boolean sortNameLocked;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "merged_into_author_id")
    private Long mergedIntoAuthorId;

    @Column(name = "normalized_name", length = 255)
    private String normalizedName;

    @Column(name = "name_locked", nullable = false)
    private boolean nameLocked;

    @Column(name = "description_locked", nullable = false)
    private boolean descriptionLocked;

    @Column(name = "asin_locked", nullable = false)
    private boolean asinLocked;

    @Column(name = "photo_locked", nullable = false)
    private boolean photoLocked;

    @ManyToMany(mappedBy = "authors", fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    @Builder.Default
    private Set<BookMetadataEntity> bookMetadataEntityList = new HashSet<>();

    @PrePersist
    @PreUpdate
    public void computeDerivedFields() {
        if (!sortNameLocked) {
            sortName = AuthorNames.clampByCodePoints(AuthorSortName.compute(name), 255);
        }
        // NOTE: renaming an author does NOT re-queue its reconcile state to PENDING here; that
        // belongs to the (not-yet-built) reconciliation phase, not this entity lifecycle callback.
        normalizedName = AuthorNames.normalizeKey(AuthorNames.cleanDisplayName(name));
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        AuthorEntity that = (AuthorEntity) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
