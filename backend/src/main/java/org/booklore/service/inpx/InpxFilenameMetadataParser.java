package org.booklore.service.inpx;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Derives a best-effort author and title from an INPX archive entry filename.
 * <p>
 * The Flibusta {@code usr} collection encodes each entry as a transliterated
 * {@code Author_Words_Title.ext} with no delimiter between the author and the title, so the split is
 * a heuristic, not a fact:
 * <ul>
 *   <li>A leading {@code _} marks a periodical / no-author item.</li>
 *   <li>An author is taken only when the first two tokens both look like a personal name; a third
 *       token joins it when the second is a Slavic patronymic ({@code -ovich}/{@code -evna}/…),
 *       which reliably marks a three-part name.</li>
 *   <li>Everything else is the title.</li>
 * </ul>
 * The result is a starting point that per-format extraction overrides where it has a value, and that
 * Smart Enrichment refines afterwards.
 */
@Component
public class InpxFilenameMetadataParser {

    private static final Pattern NAME_TOKEN = Pattern.compile("^\\p{Lu}[\\p{L}'’.\\-]*$");
    private static final Pattern PATRONYMIC = Pattern.compile("(?i)(ovich|evich|ovna|evna)\\.?$");

    public ParsedName parse(String entryName) {
        String stem = stripExtension(entryName);
        String[] tokens = stem.split("_");

        // A leading underscore (empty first token) is the periodical marker: no author.
        boolean periodical = tokens.length > 0 && tokens[0].isEmpty();

        int authorTokens = periodical ? 0 : authorTokenCount(tokens);
        // Never let the author consume the whole name — a title must remain.
        if (authorTokens >= tokens.length) {
            authorTokens = 0;
        }

        String author = authorTokens == 0 ? null : join(tokens, 0, authorTokens);
        String title = join(tokens, authorTokens, tokens.length);
        if (title.isBlank()) {
            // Author ate everything after all (e.g. an all-name filename): fall back to title-only.
            title = join(tokens, 0, tokens.length);
            author = null;
        }
        return new ParsedName(blankToNull(author), title);
    }

    private int authorTokenCount(String[] tokens) {
        if (tokens.length < 2 || !isNameToken(tokens[0]) || !isNameToken(tokens[1])) {
            return 0;
        }
        if (tokens.length >= 3 && isPatronymic(tokens[1]) && isNameToken(tokens[2])) {
            return 3;
        }
        return 2;
    }

    private boolean isNameToken(String token) {
        return NAME_TOKEN.matcher(token).matches();
    }

    private boolean isPatronymic(String token) {
        return PATRONYMIC.matcher(token).find();
    }

    private String stripExtension(String entryName) {
        String name = entryName == null ? "" : entryName.trim();
        int lastDot = name.lastIndexOf('.');
        int lastSep = Math.max(name.lastIndexOf('_'), name.lastIndexOf(' '));
        // Only strip a real extension: a trailing ".ext" after the last separator, not a dot that is
        // part of a title token such as "iyunya.".
        if (lastDot > lastSep && lastDot > 0 && lastDot < name.length() - 1) {
            return name.substring(0, lastDot);
        }
        return name;
    }

    private String join(String[] tokens, int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (tokens[i].isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tokens[i]);
        }
        return builder.toString().strip();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ParsedName(String author, String title) {
    }
}
