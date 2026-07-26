package org.booklore.service.metadata.smart;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the numeric Goodreads book id from a book URL, e.g.
 * {@code https://www.goodreads.com/book/show/104595.Montaigne_s_Travel_Journal} to {@code 104595}.
 * <p>
 * The id is the anchor the whole verification step hangs on: with it, the existing Goodreads parser
 * fetches the real rating itself instead of trusting the number the agent read off the page.
 */
public final class GoodreadsUrlParser {

    private static final Pattern BOOK_ID = Pattern.compile("goodreads\\.com/book/show/(\\d+)");

    private GoodreadsUrlParser() {
    }

    public static Optional<String> extractBookId(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = BOOK_ID.matcher(url);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
