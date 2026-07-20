package org.booklore.model.entity;

import org.booklore.model.enums.IconType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "magic_shelf", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "name"})
})
public class MagicShelfEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(name = "icon_type")
    private IconType iconType;

    @Column(name = "filter_json", columnDefinition = "json", nullable = false)
    private String filterJson;

    @Column(name = "is_public", nullable = false)
    @lombok.Builder.Default
    private boolean isPublic = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @lombok.Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneId.systemDefault());

    @Column(name = "updated_at", nullable = false)
    @lombok.Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneId.systemDefault());

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.systemDefault());
    }
}
