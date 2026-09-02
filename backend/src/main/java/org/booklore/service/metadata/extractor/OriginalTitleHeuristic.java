package org.booklore.service.metadata.extractor;

import org.apache.commons.lang3.StringUtils;
import org.booklore.util.MojibakeText;

import java.util.regex.Pattern;

/**
 * Decides whether a paragraph from an FB2 title page is the book's original-language title.
 * <p>
 * It is a heuristic over prose, so it errs towards rejecting: an FB2 body opens with whatever the
 * digitiser left there — publisher boilerplate, copyright lines, e-mail addresses, leftover HTML and
 * even web-counter JavaScript — and every one of those has the shallow shape of a title line.
 * A missing subtitle costs nothing; a paragraph of a download portal's terms stored as one is
 * visible on every screen the book appears on.
 * <p>
 * Lives apart from {@link Fb2MetadataExtractor} because the repair migration for values written by
 * the earlier, looser rule has to ask the same question, and two copies of it would drift.
 */
public final class OriginalTitleHeuristic {

    /**
     * Past this a line is prose. Real original-title lines are an author, a quoted title and a year.
     */
    private static final int MAX_LENGTH = 100;

    private static final int MIN_LATIN_LETTERS = 3;

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");

    /**
     * Markers of the things an FB2 title page carries that are not titles. {@code [<>{}=]} covers
     * leftover markup and script in one stroke, which is what the worst offenders turned out to be.
     */
    private static final Pattern BOILERPLATE_WORDS = Pattern.compile(
            "(?iu)\\bisbn\\b|\\bissn\\b|copyright|©|\\(c\\)|https?://|www\\.|@|mailto|e-?mail");

    private static final Pattern BOILERPLATE_MARKUP = Pattern.compile(
            "(?iu)[<>{}=]|\\.(?:qxp|indd|docx?|fb2|epub|txt|pdf|rtf)\\b");

    private OriginalTitleHeuristic() {
    }

    public static boolean looksLikeOriginalTitle(String value) {
        if (StringUtils.isBlank(value)
                || value.length() > MAX_LENGTH
                || MojibakeText.isMojibake(value)
                || BOILERPLATE_WORDS.matcher(value).find()
                || BOILERPLATE_MARKUP.matcher(value).find()) {
            return false;
        }
        long latinLetters = value.chars()
                .filter(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))
                .count();
        return latinLetters >= MIN_LATIN_LETTERS
                && (YEAR_PATTERN.matcher(value).find() || value.contains("«") || value.contains("\""));
    }
}
