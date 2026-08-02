package org.booklore.service.inpx;

import org.booklore.exception.ApiError;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class NestedArchiveLocator {

    static final int MAX_LENGTH = 1000;
    static final int MAX_CHAIN_ENTRIES = 6;
    private static final String PREFIX = "nested:v1:";
    private static final String DIRECT_PREFIX = "direct:v1:";

    private NestedArchiveLocator() {
    }

    static String encode(List<String> entries) {
        if (entries.size() == 1) {
            String entry = entries.getFirst();
            if (invalidEntry(entry)) {
                throw new IllegalArgumentException("Invalid archive entry");
            }
            String locator = entry.startsWith(PREFIX) || entry.startsWith(DIRECT_PREFIX)
                    ? DIRECT_PREFIX + encodeSegment(entry)
                    : entry;
            if (locator.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("Archive locator exceeds 1000 characters");
            }
            return locator;
        }
        if (entries.size() < 2 || entries.size() > MAX_CHAIN_ENTRIES
                || entries.stream().anyMatch(NestedArchiveLocator::invalidEntry)) {
            throw new IllegalArgumentException("Invalid nested archive entry chain");
        }
        String locator = PREFIX + entries.stream()
                .map(NestedArchiveLocator::encodeSegment)
                .reduce((left, right) -> left + "." + right)
                .orElseThrow();
        if (locator.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Nested archive locator exceeds 1000 characters");
        }
        return locator;
    }

    static List<String> decode(String locator) {
        if (locator == null || locator.isBlank() || locator.length() > MAX_LENGTH) {
            throw ApiError.FILE_NOT_FOUND.createException("Unsafe archived book path");
        }
        if (!locator.startsWith(PREFIX)) {
            if (locator.startsWith(DIRECT_PREFIX)) {
                try {
                    String entry = decodeSegment(locator.substring(DIRECT_PREFIX.length()));
                    if (invalidEntry(entry)) {
                        throw new IllegalArgumentException("Invalid archive entry");
                    }
                    return List.of(entry);
                } catch (IllegalArgumentException e) {
                    throw ApiError.FILE_NOT_FOUND.createException("Unsafe archived book path");
                }
            }
            if (invalidEntry(locator)) {
                throw ApiError.FILE_NOT_FOUND.createException("Unsafe archived book path");
            }
            return List.of(locator);
        }
        try {
            List<String> entries = new ArrayList<>();
            for (String segment : locator.substring(PREFIX.length()).split("\\.", -1)) {
                String entry = decodeSegment(segment);
                if (invalidEntry(entry)) {
                    throw new IllegalArgumentException("Invalid archive entry");
                }
                entries.add(entry);
            }
            if (entries.size() < 2 || entries.size() > MAX_CHAIN_ENTRIES) {
                throw new IllegalArgumentException("Nested locator requires at least two entries");
            }
            return List.copyOf(entries);
        } catch (IllegalArgumentException e) {
            throw ApiError.FILE_NOT_FOUND.createException("Unsafe archived book path");
        }
    }

    static boolean isNested(String locator) {
        return locator != null && locator.startsWith(PREFIX);
    }

    static boolean isNested(String locator, String fileName) {
        return locator != null && !locator.equals(fileName) && isNested(locator);
    }

    private static String encodeSegment(String entry) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(entry.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeSegment(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }

    private static boolean invalidEntry(String entry) {
        return entry == null || entry.isBlank() || entry.indexOf('\0') >= 0;
    }
}
