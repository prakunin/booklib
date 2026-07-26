package org.booklore.service.metadata.smart;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.smart.ResolvedWorkIdentity;

import java.util.Optional;

/**
 * Works out which literary work a file actually contains.
 * <p>
 * This is the part of enrichment that resists being written as a lookup chain: a Russian
 * translation with a digitiser's invented title has to reach an English-language rating page, and
 * the join key is neither the ISBN nor the title on disk. An interface because the agent-backed
 * implementation depends on an operator-supplied binary and cannot be the only one a shipped
 * instance has.
 */
public interface WorkIdentityResolver {

    boolean isAvailable();

    Optional<ResolvedWorkIdentity> resolve(Book book);
}
