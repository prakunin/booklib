package org.booklore.service.migration;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.booklore.model.enums.AuthorReconcileState;
import org.booklore.repository.AbstractAuthorPersistenceTest;
import org.booklore.repository.AuthorReconcileStateRepository;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.migration.migrations.PopulateAuthorNormalizedNameMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PopulateAuthorNormalizedNameMigrationTest extends AbstractAuthorPersistenceTest {

    @Autowired private AuthorRepository authorRepository;
    @Autowired private AuthorReconcileStateRepository stateRepository;
    @Autowired private PopulateAuthorNormalizedNameMigration migration;

    @Test
    void backfillsNormalizedNameAndCreatesSidecar_forLegacyNullNormalizedName() {
        // @PrePersist populates normalized_name on every save, so simulate a LEGACY pre-existing
        // row by nulling it out via a bulk JPQL update, which bypasses lifecycle callbacks.
        AuthorEntity legacy = authorRepository.saveAndFlush(AuthorEntity.builder().name("J.K. Rowling").build());
        entityManager.createQuery("UPDATE AuthorEntity a SET a.normalizedName = null WHERE a.id = :id")
                .setParameter("id", legacy.getId()).executeUpdate();
        flushAndClear();

        migration.execute();

        flushAndClear();
        AuthorEntity reloaded = authorRepository.findById(legacy.getId()).orElseThrow();
        assertThat(reloaded.getNormalizedName()).isEqualTo("j k rowling");
        assertThat(stateRepository.findById(legacy.getId()).orElseThrow().getState())
                .isEqualTo(AuthorReconcileState.PENDING);
    }

    @Test
    void createsSidecar_whenNormalizedNameAlreadySetButNoSidecarExists() {
        // F10: a plain save already sets normalized_name via @PrePersist (e.g. a legacy author
        // edited by a user before this migration ran), but does NOT create a reconcile-state
        // sidecar. The old workset (normalized_name IS NULL only) would let this author drop out
        // permanently without ever getting a sidecar. The fix is to also include "missing sidecar"
        // in the workset, so a PENDING state row must be created here.
        AuthorEntity author = authorRepository.saveAndFlush(AuthorEntity.builder().name("Neil Gaiman").build());
        String originalNormalizedName = author.getNormalizedName();
        assertThat(originalNormalizedName).isNotNull();
        assertThat(stateRepository.findById(author.getId())).isEmpty();
        flushAndClear();

        migration.execute();

        flushAndClear();
        AuthorEntity reloaded = authorRepository.findById(author.getId()).orElseThrow();
        assertThat(reloaded.getNormalizedName()).isEqualTo(originalNormalizedName);
        assertThat(stateRepository.findById(author.getId()).orElseThrow().getState())
                .isEqualTo(AuthorReconcileState.PENDING);
    }

    @Test
    void doesNotDuplicateOrOverwriteExistingSidecar() {
        // Legacy null-normalized_name author that ALREADY has a reconcile-state row, simulating a
        // prior partial/interrupted run. The migration must backfill normalized_name but must NOT
        // insert a duplicate (or overwrite the existing) state row.
        AuthorEntity legacyWithState = authorRepository.saveAndFlush(AuthorEntity.builder().name("Terry Pratchett").build());
        entityManager.createQuery("UPDATE AuthorEntity a SET a.normalizedName = null WHERE a.id = :id")
                .setParameter("id", legacyWithState.getId()).executeUpdate();
        stateRepository.saveAndFlush(AuthorReconcileStateEntity.builder()
                .authorId(legacyWithState.getId())
                .state(AuthorReconcileState.DONE)
                .attemptCount(3)
                .build());
        flushAndClear();

        migration.execute();

        flushAndClear();

        AuthorEntity reloadedLegacy = authorRepository.findById(legacyWithState.getId()).orElseThrow();
        assertThat(reloadedLegacy.getNormalizedName()).isEqualTo("terry pratchett");

        AuthorReconcileStateEntity state = stateRepository.findById(legacyWithState.getId()).orElseThrow();
        assertThat(state.getState()).isEqualTo(AuthorReconcileState.DONE);
        assertThat(state.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void neverOverwritesAnAlreadySetNormalizedName_fieldOnlyConditionalUpdate() {
        // F11: the backfill update must be field-only and conditional (WHERE normalized_name IS
        // NULL) so it can never clobber a concurrent user edit that set normalized_name between the
        // workset SELECT and this page's update. Simulate that by forcing a sentinel non-null value
        // via a bulk JPQL update (bypassing lifecycle callbacks) while the author still has no
        // sidecar, so it remains in the workset via the "missing sidecar" predicate.
        AuthorEntity author = authorRepository.saveAndFlush(AuthorEntity.builder().name("Ursula K. Le Guin").build());
        entityManager.createQuery("UPDATE AuthorEntity a SET a.normalizedName = :nn WHERE a.id = :id")
                .setParameter("nn", "stale-value")
                .setParameter("id", author.getId()).executeUpdate();
        flushAndClear();

        migration.execute();

        flushAndClear();
        AuthorEntity reloaded = authorRepository.findById(author.getId()).orElseThrow();
        assertThat(reloaded.getNormalizedName()).isEqualTo("stale-value");
        assertThat(stateRepository.findById(author.getId()).orElseThrow().getState())
                .isEqualTo(AuthorReconcileState.PENDING);
    }
}
