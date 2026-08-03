package org.booklore.service.enrichment.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The key under which the Flibusta catalog files an author biography: the MD5 of the lowercased
 * author name, UTF-8, with whitespace collapsed — {@code "Дэниел Хэндлер"} is filed as
 * {@code md5("хэндлер дэниел")}, surname first.
 * <p>
 * That is the same order the INPX importer produces: {@code InpxParser} turns the raw
 * {@code "Хэндлер,Дэниел,"} field into {@code "Хэндлер Дэниел"}, so an author name as this
 * application stores it can be hashed directly.
 * <p>
 * MD5 here is a lookup key in a third-party data file, not a security primitive; there is nothing to
 * choose, the catalog picked it.
 */
public final class FlibustaAuthorKey {

    private FlibustaAuthorKey() {
    }

    public static String of(String authorName) {
        if (authorName == null || authorName.isBlank()) {
            return null;
        }
        String normalized = authorName.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return HexFormat.of().formatHex(md5(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("java:S4790") // not a security hash: it is the lookup key the catalog files bios under
    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required to read the local catalog", e);
        }
    }
}
