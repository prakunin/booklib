package org.booklore.service.enrichment.work;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the key that decides which editions count as the same work.
 * <p>
 * Deliberately conservative. Every rule here widens the set of files that collapse onto one key, and
 * a key that collapses two genuinely different works — same author, same title, different contents,
 * which is exactly what compilations and reissues look like — will happily copy one's metadata onto
 * the other. So the normalization only removes noise that is certainly noise: case, diacritics,
 * punctuation, repeated whitespace, and a short list of edition qualifiers that carry no work
 * identity. It does not stem, transliterate or drop subtitles.
 */
public final class WorkKeys {

    private static final int MAX_KEY_LENGTH = 512;
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}«»„“”‘’—–…]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Trailing markers that describe the edition rather than the work. Anchored to the end so a
     * title that genuinely contains one of these words is untouched.
     */
    private static final List<Pattern> EDITION_QUALIFIERS = List.of(
            Pattern.compile("\\s+(сборник|антология|омнибус)$"),
            Pattern.compile("\\s+(collection|anthology|omnibus)$"),
            Pattern.compile("\\s+(том|часть|книга|volume|part|book)\\s+\\d+$"),
            Pattern.compile("\\s+\\d+\\s*(изд|издание|edition|ed)$"));

    private WorkKeys() {
    }

    /**
     * @return the key, or null when there is not enough to identify a work by — a title alone is not
     * enough, since two authors writing "Начало" are not writing the same book
     */
    public static String of(String author, String title) {
        String normalizedAuthor = normalize(author);
        String normalizedTitle = stripEditionQualifiers(normalize(title));
        if (normalizedAuthor.isEmpty() || normalizedTitle.isEmpty()) {
            return null;
        }
        String key = normalizedAuthor + "|" + normalizedTitle;
        return key.length() <= MAX_KEY_LENGTH ? key : key.substring(0, MAX_KEY_LENGTH);
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
        String withoutDiacritics = DIACRITICS.matcher(decomposed).replaceAll("");
        String withoutPunctuation = PUNCTUATION.matcher(withoutDiacritics).replaceAll(" ");
        return WHITESPACE.matcher(withoutPunctuation).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private static String stripEditionQualifiers(String normalizedTitle) {
        String result = normalizedTitle;
        for (Pattern qualifier : EDITION_QUALIFIERS) {
            result = qualifier.matcher(result).replaceAll("");
        }
        return result.strip();
    }
}
