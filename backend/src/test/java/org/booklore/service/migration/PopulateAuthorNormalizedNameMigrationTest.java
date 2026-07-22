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
    void backfillsNormalizedNameAndPendingState() {
        // @PrePersist populates normalized_name on every save, so simulate a LEGACY pre-existing
        // row by nulling it out via a bulk JPQL update, which bypasses lifecycle callbacks.
        AuthorEntity legacy = authorRepository.saveAndFlush(AuthorEntity.builder().name("J.K. Rowling").build());
        entityManager.createQuery("UPDATE AuthorEntity a SET a.normalizedName = null WHERE a.id = :id")
                .setParameter("id", legacy.getId()).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        migration.execute();

        flushAndClear();
        AuthorEntity reloaded = authorRepository.findById(legacy.getId()).orElseThrow();
        assertThat(reloaded.getNormalizedName()).isEqualTo("j k rowling");
        assertThat(stateRepository.findById(legacy.getId()).orElseThrow().getState())
                .isEqualTo(AuthorReconcileState.PENDING);
    }

    @Test
    void isRestartSafeAndDoesNotReprocessOrDuplicateExistingState() {
        // Author saved normally already has normalized_name set by @PrePersist and must be left
        // completely untouched by the migration (it is not in the normalized_name IS NULL work set).
        AuthorEntity alreadyNormalized = authorRepository.saveAndFlush(AuthorEntity.builder().name("Neil Gaiman").build());
        String originalNormalizedName = alreadyNormalized.getNormalizedName();
        assertThat(originalNormalizedName).isNotNull();

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
        entityManager.flush();
        entityManager.clear();

        migration.execute();

        flushAndClear();

        AuthorEntity reloadedNormalized = authorRepository.findById(alreadyNormalized.getId()).orElseThrow();
        assertThat(reloadedNormalized.getNormalizedName()).isEqualTo(originalNormalizedName);
        assertThat(stateRepository.findById(alreadyNormalized.getId())).isEmpty();

        AuthorEntity reloadedLegacy = authorRepository.findById(legacyWithState.getId()).orElseThrow();
        assertThat(reloadedLegacy.getNormalizedName()).isEqualTo("terry pratchett");

        AuthorReconcileStateEntity state = stateRepository.findById(legacyWithState.getId()).orElseThrow();
        assertThat(state.getState()).isEqualTo(AuthorReconcileState.DONE);
        assertThat(state.getAttemptCount()).isEqualTo(3);
    }
}
