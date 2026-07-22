package org.booklore.service.author;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorAliasRepository;
import org.booklore.repository.AuthorRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthorLocalResolverTest {

    private final AuthorRepository repo = mock(AuthorRepository.class);
    private final AuthorAliasRepository aliasRepo = mock(AuthorAliasRepository.class);
    private final AuthorCreationService creator = mock(AuthorCreationService.class);
    private final AuthorLocalResolver resolver = new AuthorLocalResolver(repo, aliasRepo, creator);

    @Nested
    class Hardening {
        @Test
        void skipsBlankToken() {
            assertThat(resolver.resolve("   ")).isEmpty();
            verifyNoInteractions(creator);
        }

        @Test
        void rejectsOverLimitTokenInsteadOfTruncating() {
            String tooLong = "a".repeat(300);
            when(repo.findByName(any())).thenReturn(Optional.empty());
            assertThat(resolver.resolve(tooLong)).isEmpty();
            verify(creator, never()).createInNewTransaction(any(), any());
        }

        @Test
        void rejectsOversizedRawInputBeforeNormalization() {
            String pathological = "a".repeat(2001);

            assertThat(resolver.resolve(pathological)).isEmpty();

            verifyNoInteractions(repo);
            verifyNoInteractions(aliasRepo);
            verifyNoInteractions(creator);
        }

        @Test
        void cleansNameBeforeLookup() {
            when(repo.findByName("James M. Ward")).thenReturn(Optional.empty());
            when(creator.createInNewTransaction("James M. Ward", "james m ward"))
                    .thenReturn(AuthorEntity.builder().id(1L).name("James M. Ward").build());

            AuthorEntity result = resolver.resolve("  James   M. Ward  ").orElseThrow();

            assertThat(result.getId()).isEqualTo(1L);
            verify(repo).findByName("James M. Ward");
        }
    }

    @Nested
    class Resolution {
        @Test
        void returnsExistingByExactName() {
            AuthorEntity existing = AuthorEntity.builder().id(7L).name("George Orwell").build();
            when(repo.findByName("George Orwell")).thenReturn(Optional.of(existing));

            assertThat(resolver.resolve("George Orwell")).contains(existing);
            verifyNoInteractions(creator);
        }

        @Test
        void createsWhenAbsent() {
            when(repo.findByName("New Author")).thenReturn(Optional.empty());
            AuthorEntity created = AuthorEntity.builder().id(9L).name("New Author").build();
            when(creator.createInNewTransaction("New Author", "new author")).thenReturn(created);

            assertThat(resolver.resolve("New Author")).contains(created);
        }

        @Test
        void reReadsWinnerWhenConcurrentInsertLosesUniqueRace() {
            AuthorEntity winner = AuthorEntity.builder().id(5L).name("Race Author").build();
            when(repo.findByName("Race Author")).thenReturn(Optional.empty()); // first check: absent
            when(creator.createInNewTransaction("Race Author", "race author"))
                    .thenThrow(new DataIntegrityViolationException("unique_name"));
            when(creator.findActiveByNameInNewTransaction("Race Author"))
                    .thenReturn(Optional.of(winner)); // fresh-tx re-read after failed insert

            assertThat(resolver.resolve("Race Author")).contains(winner);
            verify(repo).findByName("Race Author");
            verify(creator).findActiveByNameInNewTransaction("Race Author");
        }

        @Test
        void rethrowsRaceExceptionWhenFreshReReadFindsNoWinner() {
            when(repo.findByName("Ghost Author")).thenReturn(Optional.empty());
            DataIntegrityViolationException race = new DataIntegrityViolationException("some_other_constraint");
            when(creator.createInNewTransaction("Ghost Author", "ghost author")).thenThrow(race);
            when(creator.findActiveByNameInNewTransaction("Ghost Author")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resolver.resolve("Ghost Author"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isSameAs(race);
        }
    }
}
