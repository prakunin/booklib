package org.booklore.util;

import java.text.Normalizer;
import java.util.Locale;

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
