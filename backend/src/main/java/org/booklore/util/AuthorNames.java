package org.booklore.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Utilities for cleaning and normalizing author names.
 *
 * <p>Performs case-folding, diacritic stripping, and punctuation removal for display and key generation.
 * <strong>Transliteration (e.g., Cyrillic→Latin cross-script folding) is deliberately omitted</strong>;
 * script-crossing equivalence is deferred to the reconciliation layer to avoid false aliases.
 */
public final class AuthorNames {

    private AuthorNames() {
    }

    public static String cleanDisplayName(String raw) {
        if (raw == null) {
            return "";
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(nfc.length());
        nfc.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT) {
                sb.append(' '); // drop control/format, keep a boundary so tokens don't fuse
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /**
     * Produces a conservative blocking/alias key for name matching.
     *
     * <p>Applies case-folding (to ROOT locale), diacritic stripping (Unicode NFD + combining-mark removal),
     * and punctuation removal. <strong>Does not transliterate</strong> — Cyrillic, Arabic, Han, etc. remain
     * in their original scripts. Cross-script equivalence (e.g., "Иван" and "Ivan") is handled by the
     * reconciliation layer, not here, to avoid false positives within a single script.
     *
     * @param cleaned a pre-cleaned name from {@link #cleanDisplayName(String)}
     * @return normalized key for blocking/aliasing, or empty string if the input is null or blank
     */
    public static String normalizeKey(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return "";
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        String stripped = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");         // combining marks -> diacritics gone
        return stripped.replaceAll("[\\p{Punct}]", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public static String clampByCodePoints(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    public static boolean isBlank(String cleaned) {
        return cleanDisplayName(cleaned).isEmpty();
    }
}
