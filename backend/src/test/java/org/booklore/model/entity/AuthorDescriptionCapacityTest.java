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
 * An author biography is long-form prose written by somebody else, and the local catalog ships some
 * very long ones: 102 of that catalog's 56,853 biographies are larger than {@code TEXT} can hold and
 * the longest is 559,153 bytes. The biography is written inside the book's enrichment transaction,
 * so one that does not fit does not merely lose the biography — it rolls the whole book back and
 * costs it its description, series and reviews too.
 * <p>
 * These tests pin the capacity rather than the spelling of a type: what matters is how many bytes
 * the column accepts, and that the schema the migrations produce is the schema the entity declares.
 */
class AuthorDescriptionCapacityTest {

    /** {@code TEXT} in MySQL/MariaDB, in bytes — not characters, which is what made Cyrillic overflow. */
    private static final long TEXT_CAPACITY_BYTES = 65_535L;

    /** The largest author biography measured in the shipped {@code fb2.Flibusta.Net.FLibrary.etc} catalog. */
    private static final long LONGEST_KNOWN_BIOGRAPHY_BYTES = 559_153L;

    private static final Pattern ALTER_AUTHOR_DESCRIPTION = Pattern.compile(
            "ALTER\\s+TABLE\\s+`?author`?\\s+MODIFY\\s+(?:COLUMN\\s+)?`?description`?\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE);

    @Nested
    class TheColumnTheEntityDeclares {

        @Test
        void holdsTheLongestBiographyTheLocalCatalogShips() {
            assertThat(capacityOf(declaredColumnDefinition()))
                    .as("author.description must hold the longest biography in the local catalog")
                    .isGreaterThanOrEqualTo(LONGEST_KNOWN_BIOGRAPHY_BYTES);
        }

        @Test
        void holdsMoreThanTextCanHold() {
            assertThat(capacityOf(declaredColumnDefinition()))
                    .as("TEXT is what overflowed; the column must be wider than that")
                    .isGreaterThan(TEXT_CAPACITY_BYTES);
        }
    }

    @Nested
    class TheColumnTheMigrationsProduce {

        @Test
        void endUpAtTheSameTypeTheEntityDeclares() throws IOException {
            assertThat(lastMigratedType())
                    .as("the schema the migrations build must match the schema the entity maps")
                    .isEqualToIgnoringCase(declaredColumnDefinition());
        }

        @Test
        void holdMoreThanTextCanHold() throws IOException {
            assertThat(capacityOf(lastMigratedType()))
                    .as("the last migration touching author.description must leave it wider than TEXT")
                    .isGreaterThan(TEXT_CAPACITY_BYTES);
        }
    }

    /**
     * A Cyrillic biography is about two bytes per character, which is why one that looks short in
     * characters can still overflow a byte-counted column.
     */
    @Test
    void countsCyrillicBiographiesInBytesRatherThanCharacters() {
        String biography = "б".repeat(40_000);

        assertThat(biography.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan((int) TEXT_CAPACITY_BYTES);
        assertThat(capacityOf(declaredColumnDefinition()))
                .isGreaterThan((long) biography.getBytes(StandardCharsets.UTF_8).length);
    }

    private static String declaredColumnDefinition() {
        try {
            Field field = AuthorEntity.class.getDeclaredField("description");
            Column column = field.getAnnotation(Column.class);
            assertThat(column).as("author description must be an explicitly mapped column").isNotNull();
            return column.columnDefinition();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("AuthorEntity no longer has a description field", e);
        }
    }

    private static String lastMigratedType() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        List<Resource> ordered = Arrays.stream(migrations)
                .sorted(Comparator.comparingInt(AuthorDescriptionCapacityTest::versionOf))
                .toList();

        String type = null;
        for (Resource migration : ordered) {
            String sql = migration.getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = ALTER_AUTHOR_DESCRIPTION.matcher(sql);
            while (matcher.find()) {
                type = matcher.group(1);
            }
        }
        assertThat(type)
                .as("no migration alters author.description, so it is still whatever created it")
                .isNotNull();
        return type;
    }

    private static int versionOf(Resource migration) {
        String name = Objects.requireNonNull(migration.getFilename());
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    private static long capacityOf(String columnType) {
        return switch (columnType.toUpperCase(Locale.ROOT)) {
            case "TINYTEXT" -> 255L;
            case "TEXT" -> TEXT_CAPACITY_BYTES;
            case "MEDIUMTEXT" -> 16_777_215L;
            case "LONGTEXT" -> 4_294_967_295L;
            default -> throw new AssertionError("unknown column type for author.description: " + columnType);
        };
    }
}
