package org.booklore.service.author;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorRepository;
import org.booklore.util.AuthorNames;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorLocalResolver {

    private static final int MAX_NAME_CODE_POINTS = 255;

    private final AuthorRepository authorRepository;
    private final AuthorCreationService authorCreationService;

    public Optional<AuthorEntity> resolve(String rawName) {
        String cleaned = AuthorNames.cleanDisplayName(rawName);
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_NAME_CODE_POINTS) {
            log.warn("Skipping over-limit author name ({} code points): '{}'",
                    cleaned.codePointCount(0, cleaned.length()), sample(cleaned));
            return Optional.empty();
        }
        String normalized = AuthorNames.normalizeKey(cleaned);

        Optional<AuthorEntity> existing = authorRepository.findByName(cleaned);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            return Optional.of(authorCreationService.createInNewTransaction(cleaned, normalized));
        } catch (DataIntegrityViolationException race) {
            // Another transaction won the unique_name race; re-read the committed winner.
            return authorRepository.findByName(cleaned);
        }
    }

    private static String sample(String value) {
        String clamped = AuthorNames.clampByCodePoints(value, 60);
        return clamped.replaceAll("\\p{Cntrl}", "?");
    }
}
