package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "author_alias",
        uniqueConstraints = @UniqueConstraint(name = "uq_author_alias",
                columnNames = {"author_id", "normalized_alias", "language", "source"}))
public class AuthorAliasEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "normalized_alias", nullable = false, length = 255)
    private String normalizedAlias;

    @Column(name = "language", nullable = false, length = 35)
    @Builder.Default
    private String language = "und";

    @Column(name = "kind", length = 32)
    private String kind;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "resolvable", nullable = false)
    private boolean resolvable;
}
