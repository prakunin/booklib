package org.booklore.service.migration;

import org.booklore.model.entity.AuthorEntity;
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
        AuthorEntity a = authorRepository.saveAndFlush(AuthorEntity.builder().name("J.K. Rowling").build());

        migration.execute();

        AuthorEntity reloaded = authorRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getNormalizedName()).isEqualTo("j k rowling");
        assertThat(stateRepository.findById(a.getId()).orElseThrow().getState())
                .isEqualTo(AuthorReconcileState.PENDING);
    }
}
