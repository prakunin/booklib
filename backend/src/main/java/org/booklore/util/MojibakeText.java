package org.booklore.util;

import org.apache.commons.lang3.StringUtils;

/**
 * Recognises text whose characters were destroyed before it ever reached us.
 * <p>
 * A file written by a converter that guessed the wrong charset stores U+FFFD where each character
 * used to be, and the damage is permanent: nothing downstream can tell which byte was lost. Such a
 * value is worse than an absent one, because every caller treats a non-blank string as a real value
 * and will happily write it over a catalog record that is still intact.
 * <p>
 * The test is deliberately a ratio and not "contains U+FFFD": a single stray replacement character
 * in an otherwise readable title is a blemish, not a destroyed value, and blanking it would lose
 * more than it saves.
 */
public final class MojibakeText {

    private static final char REPLACEMENT_CHAR = '�';

    /**
     * Below this many replacement characters the value is treated as readable regardless of ratio,
     * so that a short title with one or two damaged letters survives.
     */
    private static final int MIN_REPLACEMENT_CHARS = 3;

    private static final double MIN_REPLACEMENT_RATIO = 0.25;

    private MojibakeText() {
    }

    public static boolean isMojibake(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        long replacements = value.chars().filter(ch -> ch == REPLACEMENT_CHAR).count();
        if (replacements < MIN_REPLACEMENT_CHARS) {
            return false;
        }
        long visible = value.chars().filter(ch -> !Character.isWhitespace(ch)).count();
        return visible > 0 && (double) replacements / visible >= MIN_REPLACEMENT_RATIO;
    }

    /**
     * @return the value unchanged, or {@code null} when it is mojibake — the shape callers want when
     * an absent value means "keep whatever is already stored".
     */
    public static String scrub(String value) {
        return isMojibake(value) ? null : value;
    }
}
