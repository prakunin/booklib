package org.booklore.model.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local-catalog backfill pages through a library with a keyset cursor ordered by
 * {@code (source_archive, source_archive_entry, book_id)}. A keyset cursor is only cheap if an
 * index establishes that order; a <em>prefix</em> index does not, because rows sharing the indexed
 * prefix are unordered within it, so MariaDB sorts the whole 704,575-row table on every page.
 * Measured, that was 5.006 s per 500-book page — flat with depth, which is the signature of a sort
 * rather than a walk — against 2 ms once the index holds the columns in full.
 * <p>
 * Indexing them in full is only possible while they are narrow enough: InnoDB's key limit is 3072
 * bytes and utf8mb4 costs 4 bytes per character, so these tests pin the two facts that together
 * keep the fast plan reachable — the declared widths fit in one key, and the migrations declare the
 * same widths the entity does. Widening either column back towards {@code VARCHAR(1000)}, or
 * re-declaring the index by prefix, silently makes the backfill sort again, and that is what fails
 * here.
 */
class BookFileArchiveCursorIndexTest {

    /** InnoDB's maximum index key length, DYNAMIC row format on a 16 KB page. */
    private static final int INNODB_MAX_KEY_BYTES = 3072;

    /** utf8mb4 worst case, which is what {@code book_file}'s columns are declared in. */
    private static final int BYTES_PER_CHARACTER = 4;

    /** The longest values measured across all 704,575 rows of the dev library. */
    private static final int LONGEST_KNOWN_ARCHIVE_NAME = 23;
    private static final int LONGEST_KNOWN_ENTRY_NAME = 120;

    private static final String CURSOR_INDEX_NAME = "idx_book_file_archive_cursor";

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    @Nested
    class TheColumnsTheEntityDeclares {

        @Test
        void holdTheLongestArchiveAndEntryNamesTheLibraryContains() {
            assertThat(declaredLength("sourceArchive")).isGreaterThan(LONGEST_KNOWN_ARCHIVE_NAME);
            assertThat(declaredLength("sourceArchiveEntry")).isGreaterThan(LONGEST_KNOWN_ENTRY_NAME);
        }

        @Test
        void fitTogetherWithBookIdInOneInnodbIndexKey() {
            int key = keyBytes(declaredLength("sourceArchive"))
                    + keyBytes(declaredLength("sourceArchiveEntry")) + Long.BYTES;

            assertThat(key)
                    .as("(source_archive, source_archive_entry, book_id) must be indexable in full, "
                            + "or the cursor goes back to sorting the whole table per page")
                    .isLessThanOrEqualTo(INNODB_MAX_KEY_BYTES);
        }
    }

    @Nested
    class TheColumnsTheMigrationsProduce {

        @Test
        void endUpAtTheSameWidthsTheEntityDeclares() throws IOException {
            assertThat(lastMigratedLength("source_archive")).isEqualTo(declaredLength("sourceArchive"));
            assertThat(lastMigratedLength("source_archive_entry")).isEqualTo(declaredLength("sourceArchiveEntry"));
        }

        @Test
        void fitTogetherWithBookIdInOneInnodbIndexKey() throws IOException {
            int key = keyBytes(lastMigratedLength("source_archive"))
                    + keyBytes(lastMigratedLength("source_archive_entry")) + Long.BYTES;

            assertThat(key).isLessThanOrEqualTo(INNODB_MAX_KEY_BYTES);
        }
    }

    @Nested
    class TheIndexTheMigrationsProduce {

        @Test
        void coversTheCursorsThreeOrderingColumnsInOrder() throws IOException {
            assertThat(cursorIndexColumns())
                    .containsExactly("source_archive", "source_archive_entry", "book_id");
        }

        /**
         * The whole point. {@code source_archive(255)} in the column list reads as if it covered the
         * column, but it is a prefix and MariaDB will still filesort behind it.
         */
        @Test
        void indexesThoseColumnsInFullRatherThanByPrefix() throws IOException {
            assertThat(cursorIndexColumnList())
                    .as("a prefix index cannot establish an ordering, so it cannot serve the cursor")
                    .doesNotContain("(");
        }
    }

    private static int keyBytes(int characters) {
        // utf8mb4 payload, 2 bytes of length prefix, 1 byte for the NULL flag on a nullable column.
        return characters * BYTES_PER_CHARACTER + 2 + 1;
    }

    private static int declaredLength(String fieldName) {
        try {
            Field field = BookFileEntity.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            assertThat(column).as(fieldName + " must be an explicitly mapped column").isNotNull();
            return column.length();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("BookFileEntity no longer has a " + fieldName + " field", e);
        }
    }

    private static int lastMigratedLength(String columnName) throws IOException {
        Pattern declaration = Pattern.compile(
                "(?:ADD|MODIFY)\\s+(?:COLUMN\\s+)?`?" + columnName + "`?\\s+VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)",
                Pattern.CASE_INSENSITIVE);

        Integer length = null;
        for (Resource migration : orderedMigrations()) {
            Matcher matcher = declaration.matcher(statementsOf(migration));
            while (matcher.find()) {
                length = Integer.parseInt(matcher.group(1));
            }
        }
        assertThat(length).as("no migration declares book_file." + columnName).isNotNull();
        return length;
    }

    private static List<String> cursorIndexColumns() throws IOException {
        return Arrays.stream(cursorIndexColumnList().split(","))
                .map(column -> column.trim().replace("`", "").toLowerCase(Locale.ROOT))
                .toList();
    }

    /** The column list the migrations give {@value #CURSOR_INDEX_NAME}, exactly as written. */
    private static String cursorIndexColumnList() throws IOException {
        Pattern creation = Pattern.compile(
                "CREATE\\s+INDEX(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+`?" + CURSOR_INDEX_NAME
                        + "`?\\s+ON\\s+`?book_file`?\\s*\\(([^;']*)\\)",
                Pattern.CASE_INSENSITIVE);

        String columns = null;
        for (Resource migration : orderedMigrations()) {
            Matcher matcher = creation.matcher(statementsOf(migration));
            while (matcher.find()) {
                columns = matcher.group(1);
            }
        }
        assertThat(columns)
                .as("no migration creates " + CURSOR_INDEX_NAME + " on book_file, so the backfill's "
                        + "cursor has no index that can establish its ordering")
                .isNotNull();
        return columns;
    }

    /** Migration text with {@code --} comments removed, so prose about SQL is not read as SQL. */
    private static String statementsOf(Resource migration) throws IOException {
        return LINE_COMMENT.matcher(migration.getContentAsString(StandardCharsets.UTF_8)).replaceAll("");
    }

    private static List<Resource> orderedMigrations() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        return Arrays.stream(migrations)
                .sorted(Comparator.comparingInt(BookFileArchiveCursorIndexTest::versionOf))
                .toList();
    }

    private static int versionOf(Resource migration) {
        String name = Objects.requireNonNull(migration.getFilename());
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
