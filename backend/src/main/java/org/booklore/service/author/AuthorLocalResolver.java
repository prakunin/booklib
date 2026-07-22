package org.booklore.service.author;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.AuthorAliasEntity;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.repository.AuthorAliasRepository;
import org.booklore.repository.AuthorRepository;
import org.booklore.util.AuthorNames;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorLocalResolver {

    private static final int MAX_NAME_CODE_POINTS = 255;

    private final AuthorRepository authorRepository;
    private final AuthorAliasRepository authorAliasRepository;
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

        // 1. exact name -> follow redirect to the active root
        Optional<AuthorEntity> byName = authorRepository.findByName(cleaned);
        if (byName.isPresent()) {
            return Optional.of(resolveToActiveRoot(byName.get()));
        }

        // 2. confirmed, unambiguous alias
        List<AuthorAliasEntity> aliasHits = authorAliasRepository.findByNormalizedAliasAndResolvableTrue(normalized);
        long distinctAuthors = aliasHits.stream().map(AuthorAliasEntity::getAuthorId).distinct().count();
        if (distinctAuthors == 1) {
            return authorRepository.findById(aliasHits.get(0).getAuthorId())
                    .map(this::resolveToActiveRoot);
        }
        // distinctAuthors > 1 -> ambiguous: fall through to provisional create (do not choose one)

        // 3. create provisional
        try {
            return Optional.of(authorCreationService.createInNewTransaction(cleaned, normalized));
        } catch (DataIntegrityViolationException race) {
            // Re-read in a FRESH transaction: a REPEATABLE READ snapshot opened by the first lookup
            // would not see the row a concurrent transaction just committed.
            Optional<AuthorEntity> winner = authorCreationService.findActiveByNameInNewTransaction(cleaned);
            if (winner.isPresent()) {
                return winner.map(this::resolveToActiveRoot);
            }
            // No winner => this was NOT a unique-name race (e.g. a length/constraint failure). Surface it
            // instead of silently dropping the author.
            throw race;
        }
    }

    private AuthorEntity resolveToActiveRoot(AuthorEntity author) {
        AuthorEntity current = author;
        Set<Long> seen = new HashSet<>();
        while (current.getMergedIntoAuthorId() != null) {
            if (!seen.add(current.getId())) {
                log.error("Redirect cycle detected at author id {}; stopping at it", current.getId());
                return current;
            }
            AuthorEntity next = authorRepository.findById(current.getMergedIntoAuthorId()).orElse(null);
            if (next == null) {
                log.warn("Dangling redirect: author {} -> missing {}", current.getId(), current.getMergedIntoAuthorId());
                return current;
            }
            current = next;
        }
        return current;
    }

    private static String sample(String value) {
        String clamped = AuthorNames.clampByCodePoints(value, 60);
        return clamped.replaceAll("\\p{Cntrl}", "?");
    }
}
