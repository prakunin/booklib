package org.booklore.service.metadata.smart;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.BookMetadata;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the resolution prompt.
 * <p>
 * The prompt carries two rules that the rest of the pipeline depends on. First, the agent is asked
 * to identify the <em>work</em>, not the edition on disk: a rating is a property of the work, so a
 * Russian translation has to be traced back to its original before any rating is meaningful.
 * Second, free text must be quoted from a page rather than composed, because a generated summary of
 * a book the model half-remembers is indistinguishable from a real blurb once it lands in the
 * description field.
 */
@Component
public class WorkIdentityPromptBuilder {

    /**
     * The thorough mode: the agent searches the web and reads pages. Accurate — it can quote a real
     * description and confirm an edition — but slow and quota-heavy, because every opened page's text
     * enters the model context. Capped at a few pages so a run does not spiral.
     */
    private static final String DEEP_TASK_AND_RULES = """

            TASK
            Using web search, identify the literary WORK this file contains, not the particular edition.
            A translated edition must be traced back to the original work, because ratings and
            first-publication facts belong to the work rather than to one translation.
            Then, separately, find the EDITION this file was made from — the one matching the input
            language, and the input publisher or year when those are given.
            When the title and authors are missing, the "book_excerpt" is the strongest clue: it is the
            real opening text of the file, and the title page there usually prints author, title, series
            and publisher outright. Read it first. The "filename" is a weaker fallback (author, series,
            number, title as "Author. Series 1. Title"). Use both for search terms, then confirm what
            they suggest on a real page — never copy a value straight out of the filename.

            RULES
            1. Reply with a single JSON object and nothing else. No prose, no markdown fence.
            2. Never invent a value. Use null for anything you could not confirm on a page you opened.
               A field you did not look for is null, not a guess that looks reasonable. The filename
               is a hint for searching, not a source you can confirm a value from.
            3. "description" must be copied verbatim from a source page. Never write your own summary.
               Prefer the publisher, the author's site, a national library, or a major bookshop over
               aggregator or file-sharing sites. Record where you took it from.
            4. The edition fields (edition_title, edition_author, edition_language, publisher,
               published_date, isbn13, isbn10, page_count) describe one concrete release. Give the
               title and author exactly as they appear ON THAT release and in ITS language — for a
               Russian edition of an English novel, edition_title is the Russian title and
               edition_language is "ru", while original_title stays the English one. If you cannot
               find a release matching the input language, leave every edition field null rather
               than reporting a different edition's values.
            5. An ISBN must be copied digit for digit from the page. Its check digit is validated on
               arrival and a mismatch discards it, so a reconstructed or partially remembered number is
               worse than null.
            6. "series_name" is the series the work belongs to, in the language of the input file when
               such a name exists. Leave it null for a standalone book.
            7. "genres" holds at most five short subject labels as a source page states them.
            8. Open at most 3 pages total — enough to confirm the identity and copy one description.
               "sources" lists exactly the URLs you actually opened, not ones you assume exist.
            """;

    /**
     * The default mode: no web access. The agent answers only from what it already knows, which is
     * far cheaper. It is told NOT to produce a description — without a page to quote, any description
     * is invented, and a plausible fabricated blurb is exactly what the whole design forbids.
     */
    private static final String QUICK_TASK_AND_RULES = """

            TASK
            Identify, FROM YOUR OWN KNOWLEDGE ONLY, the literary WORK this file contains and the
            edition it was made from. Do NOT search the web or open any pages — this is a fast,
            low-cost pass that must not spend a single web request.
            The "book_excerpt" — the real opening text of the file — is your best evidence: the title
            page there usually prints author, title, series and publisher outright, so read it and take
            what it plainly states. The "filename" is a weaker fallback (author, series, number, title
            as "Author. Series 1. Title"). Use both, but only commit to what the excerpt actually says
            or what you already reliably know.

            RULES
            1. Reply with a single JSON object and nothing else. No prose, no markdown fence.
            2. Never invent a value. Use null for anything you are not sure of — a guess that looks
               reasonable is worse than null. The filename is a hint, not a confirmed source.
            3. Do NOT write a description. Without a page to quote it would be your own invention, which
               is not allowed here. Always leave "description", "description_language" and
               "description_source_url" null.
            4. Leave "sources" an empty array — you opened nothing.
            5. Give edition_title, edition_author and edition_language in the file's own language when
               you know the release (for a Russian edition of an English novel, edition_title is the
               Russian title, edition_language is "ru"); original_* is the original composition.
            6. An ISBN must be one you remember digit for digit; its check digit is validated and a
               mismatch is discarded, so an approximate number is worse than null.
            7. "genres" holds at most five short subject labels.
            """;

    public String build(Book book, boolean deepSearch, String excerpt) {
        BookMetadata metadata = book.getMetadata();
        StringBuilder prompt = new StringBuilder(1024);

        prompt.append("You are a book metadata resolver. The input below was extracted from an ebook file ")
                .append("and may be incomplete, mistyped, or carry a title invented by whoever digitised it.\n\n")
                .append("INPUT\n");
        appendField(prompt, "title", metadata == null ? book.getTitle() : metadata.getTitle());
        appendField(prompt, "subtitle", metadata == null ? null : metadata.getSubtitle());
        appendField(prompt, "authors", metadata == null ? null : joinAuthors(metadata.getAuthors()));
        appendField(prompt, "language", metadata == null ? null : metadata.getLanguage());
        appendField(prompt, "series", metadata == null ? null : metadata.getSeriesName());
        appendField(prompt, "isbn", metadata == null ? null : firstNonBlank(metadata.getIsbn13(), metadata.getIsbn10()));
        appendField(prompt, "publisher", metadata == null ? null : metadata.getPublisher());
        appendField(prompt, "published_date", metadata == null || metadata.getPublishedDate() == null
                ? null : metadata.getPublishedDate().toString());
        // The filename is often the only real signal: FB2/EPUB files from file-sharing sites
        // routinely carry empty internal metadata while the filename encodes author, series, number
        // and title (e.g. "Хантер. Красные шатры 1. Рассвет рыцаря"). It is a lead, not a fact — the
        // prompt rules tell the agent to treat it as a search hint, not a value to copy.
        appendField(prompt, "filename", fileNameWithoutExtension(book));
        // Truncated: a stored blurb is a useful identifying fingerprint, but a full one would
        // dominate the prompt without telling the agent anything the opening lines do not.
        appendField(prompt, "existing_description", truncate(metadata == null ? null : metadata.getDescription()));

        // The opening pages of the file. This is the strongest single signal when title-info is empty
        // — the title page usually prints author, title, series and publisher verbatim — and it is
        // what lets the cheap no-web mode identify the book at all. Appended as a delimited block so
        // its own line breaks do not blur the INPUT fields above.
        if (excerpt != null && !excerpt.isBlank()) {
            prompt.append("\nbook_excerpt (opening pages of the file):\n<<<\n")
                    .append(excerpt.strip())
                    .append("\n>>>\n");
        }

        prompt.append(deepSearch ? DEEP_TASK_AND_RULES : QUICK_TASK_AND_RULES);

        prompt.append("""

                SCHEMA
                {
                  "original_title": string|null,
                  "original_author": string|null,
                  "original_language": string|null,
                  "edition_title": string|null,
                  "edition_author": string|null,
                  "edition_language": string|null,
                  "first_published_year": number|null,
                  "goodreads_url": string|null,
                  "reported_rating": number|null,
                  "description": string|null,
                  "description_language": string|null,
                  "description_source_url": string|null,
                  "publisher": string|null,
                  "published_date": "YYYY-MM-DD"|"YYYY"|null,
                  "isbn13": string|null,
                  "isbn10": string|null,
                  "page_count": number|null,
                  "series_name": string|null,
                  "series_number": number|null,
                  "series_total": number|null,
                  "genres": [string]|null,
                  "sources": [string]
                }
                """);
        return prompt.toString();
    }

    private void appendField(StringBuilder prompt, String name, String value) {
        prompt.append(name).append(": ").append(value == null || value.isBlank() ? "(missing)" : value).append('\n');
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }

    private String joinAuthors(List<String> authors) {
        return authors == null || authors.isEmpty() ? null : String.join(", ", authors);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    /**
     * The primary file's name with its extension stripped. Underscores are turned back into spaces
     * because many digitisers use them as separators, and the agent parses the string as prose.
     */
    private String fileNameWithoutExtension(Book book) {
        BookFile primaryFile = book.getPrimaryFile();
        if (primaryFile == null || primaryFile.getFileName() == null || primaryFile.getFileName().isBlank()) {
            return null;
        }
        String name = primaryFile.getFileName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }
        return name.replace('_', ' ').trim();
    }
}
