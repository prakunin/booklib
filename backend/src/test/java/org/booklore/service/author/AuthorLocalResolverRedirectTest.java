package org.booklore.service.author;

import org.booklore.model.entity.AuthorAliasEntity;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorAliasRepository;
import org.booklore.repository.AuthorRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthorLocalResolverRedirectTest {

    private final AuthorRepository repo = mock(AuthorRepository.class);
    private final AuthorAliasRepository aliasRepo = mock(AuthorAliasRepository.class);
    private final AuthorCreationService creator = mock(AuthorCreationService.class);
    private final AuthorLocalResolver resolver = new AuthorLocalResolver(repo, aliasRepo, creator);

    @Nested
    class Redirect {
        @Test
        void followsRedirectToCanonical() {
            AuthorEntity canonical = AuthorEntity.builder().id(1L).name("James M. Ward").build();
            AuthorEntity loser = AuthorEntity.builder().id(2L).name("Jim Ward").mergedIntoAuthorId(1L).build();
            when(repo.findByName("Jim Ward")).thenReturn(Optional.of(loser));
            when(repo.findById(1L)).thenReturn(Optional.of(canonical));

            assertThat(resolver.resolve("Jim Ward")).contains(canonical);
            verifyNoInteractions(creator);
        }
    }

    @Nested
    class Alias {
        @Test
        void resolvesConfirmedUniqueAlias() {
            AuthorEntity canonical = AuthorEntity.builder().id(1L).name("James M. Ward").build();
            when(repo.findByName("Jim M Ward")).thenReturn(Optional.empty());
            when(aliasRepo.findByNormalizedAliasAndResolvableTrue("jim m ward"))
                    .thenReturn(List.of(AuthorAliasEntity.builder().authorId(1L).build()));
            when(repo.findById(1L)).thenReturn(Optional.of(canonical));

            assertThat(resolver.resolve("Jim M Ward")).contains(canonical);
            verifyNoInteractions(creator);
        }

        @Test
        void ambiguousAliasCreatesProvisionalInsteadOfChoosing() {
            when(repo.findByName("Ambiguous Name")).thenReturn(Optional.empty());
            when(aliasRepo.findByNormalizedAliasAndResolvableTrue("ambiguous name"))
                    .thenReturn(List.of(
                            AuthorAliasEntity.builder().authorId(1L).build(),
                            AuthorAliasEntity.builder().authorId(2L).build()));
            AuthorEntity provisional = AuthorEntity.builder().id(3L).name("Ambiguous Name").build();
            when(creator.createInNewTransaction("Ambiguous Name", "ambiguous name")).thenReturn(provisional);

            assertThat(resolver.resolve("Ambiguous Name")).contains(provisional);
            verify(creator).createInNewTransaction("Ambiguous Name", "ambiguous name");
        }
    }

    @Nested
    class CycleGuard {
        @Test
        void selfRedirectDoesNotLoopAndReturnsSameAuthor() {
            AuthorEntity selfLoop = AuthorEntity.builder().id(1L).name("Loopy").mergedIntoAuthorId(1L).build();
            when(repo.findByName("Loopy")).thenReturn(Optional.of(selfLoop));
            when(repo.findById(1L)).thenReturn(Optional.of(selfLoop));

            assertThat(resolver.resolve("Loopy")).contains(selfLoop);
            verifyNoInteractions(creator);
        }

        @Test
        void twoNodeCycleTerminatesAndReturnsLastVisited() {
            AuthorEntity a = AuthorEntity.builder().id(1L).name("A").mergedIntoAuthorId(2L).build();
            AuthorEntity b = AuthorEntity.builder().id(2L).name("B").mergedIntoAuthorId(1L).build();
            when(repo.findByName("A")).thenReturn(Optional.of(a));
            when(repo.findById(2L)).thenReturn(Optional.of(b));
            when(repo.findById(1L)).thenReturn(Optional.of(a));

            assertThat(resolver.resolve("A")).contains(a);
            verifyNoInteractions(creator);
        }

        @Test
        void danglingRedirectReturnsLastGoodNode() {
            AuthorEntity a = AuthorEntity.builder().id(1L).name("A").mergedIntoAuthorId(99L).build();
            when(repo.findByName("A")).thenReturn(Optional.of(a));
            when(repo.findById(99L)).thenReturn(Optional.empty());

            assertThat(resolver.resolve("A")).contains(a);
            verifyNoInteractions(creator);
        }
    }
}
