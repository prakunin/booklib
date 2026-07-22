package org.booklore.service.book;

import org.booklore.repository.*;
import org.booklore.service.author.AuthorLocalResolver;

import static org.mockito.Mockito.mock;

/**
 * Builds a {@link BookCreatorService} with mocked collaborators for tests that only
 * care about a single method (e.g. author resolution), without every test having to
 * repeat the full constructor's mock wiring.
 */
final class BookCreatorServiceTestFactory {

    private BookCreatorServiceTestFactory() {
    }

    static BookCreatorService withResolver(AuthorLocalResolver resolver) {
        return new BookCreatorService(
                mock(AuthorRepository.class),
                mock(CategoryRepository.class),
                mock(MoodRepository.class),
                mock(TagRepository.class),
                mock(BookRepository.class),
                mock(BookMetadataRepository.class),
                mock(ComicMetadataRepository.class),
                mock(ComicCharacterRepository.class),
                mock(ComicTeamRepository.class),
                mock(ComicLocationRepository.class),
                mock(ComicCreatorRepository.class),
                resolver);
    }
}
