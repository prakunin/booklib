package org.booklore.service.enrichment.catalog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Reads one per-language listing from the Flibusta catalog's {@code contents.7z}.
 * <p>
 * The columns are author ⇥ title ⇥ [series] ⇥ archive ⇥ file, and only the last two carry the join
 * key. The language itself is not in the document at all — it is the name of the file the rows came
 * from, so the caller supplies it.
 * <p>
 * {@code ru.txt} alone is around 90 MB, so rows are handed to the consumer as they are read and
 * nothing accumulates here.
 */
@Slf4j
@Component
public class FlibustaContentsParser {

    private static final int MIN_COLUMNS = 5;
    private static final int ARCHIVE_COLUMN_FROM_END = 2;
    private static final int ENTRY_COLUMN_FROM_END = 1;

    /**
     * @param consumer receives {@code (archiveName, entryName)} for every usable row
     * @return how many rows were accepted
     */
    public int parse(InputStream tsv, BiConsumer<String, String> consumer) {
        int accepted = 0;
        int rejected = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tsv, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (accept(line, consumer)) {
                    accepted++;
                } else if (!line.isBlank()) {
                    rejected++;
                }
            }
        } catch (Exception e) {
            log.warn("Could not read a contents listing after {} rows: {}", accepted, e.getMessage());
        }
        if (rejected > 0) {
            log.debug("Skipped {} unusable rows in a contents listing", rejected);
        }
        return accepted;
    }

    private boolean accept(String line, BiConsumer<String, String> consumer) {
        if (line.isBlank()) {
            return false;
        }
        String[] columns = line.split("\t", -1);
        if (columns.length < MIN_COLUMNS) {
            return false;
        }
        String archive = columns[columns.length - ARCHIVE_COLUMN_FROM_END].strip();
        String entry = columns[columns.length - ENTRY_COLUMN_FROM_END].strip();
        if (archive.isEmpty() || entry.isEmpty()) {
            return false;
        }
        consumer.accept(archive, entry);
        return true;
    }
}
