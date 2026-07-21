package org.booklore.service.inpx;

import java.util.Set;

import static java.util.Objects.requireNonNull;

public record InpxCoverGenerationRequestedEvent(Set<Long> bookIds, String username) {

    public InpxCoverGenerationRequestedEvent {
        bookIds = Set.copyOf(requireNonNull(bookIds));
        requireNonNull(username);
    }
}
