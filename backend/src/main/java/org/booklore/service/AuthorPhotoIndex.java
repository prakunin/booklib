package org.booklore.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Caches the set of author ids that currently have a thumbnail on disk so the hot author-listing
 * path can answer {@code hasPhoto} without a {@link java.nio.file.Files#exists} call per author.
 *
 * <p>The filesystem stays the source of truth; a short write-expiry means a missed invalidation
 * self-heals within a minute, unlike a persisted flag that would stay stale until the photo is
 * rewritten.
 */
@Service
@RequiredArgsConstructor
public class AuthorPhotoIndex {

    private static final Object KEY = new Object();

    private final FileService fileService;
    private final Cache<Object, Set<Long>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(1)
            .build();

    public boolean hasPhoto(long authorId) {
        return snapshot().contains(authorId);
    }

    public Set<Long> authorIdsWithPhoto() {
        return snapshot();
    }

    public void invalidate() {
        cache.invalidate(KEY);
    }

    private Set<Long> snapshot() {
        return cache.get(KEY, ignored -> fileService.listAuthorIdsWithThumbnail());
    }
}
