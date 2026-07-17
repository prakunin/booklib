package org.booklore.config;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationVersionTest {

    @Test
    void migrationVersionsAreUnique() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");

        assertThat(migrations)
                .as("Flyway migrations must be present on the test classpath")
                .isNotEmpty();

        Map<MigrationVersion, List<String>> duplicateVersions = Arrays.stream(migrations)
                .collect(Collectors.groupingBy(
                        migration -> versionOf(migration.getFilename()),
                        Collectors.mapping(Resource::getFilename, Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicateVersions)
                .as("Flyway migration versions must be unique")
                .isEmpty();
    }

    private MigrationVersion versionOf(String filename) {
        Objects.requireNonNull(filename, "Migration resource must have a filename");
        return MigrationVersion.fromVersion(filename.substring(1, filename.indexOf("__")));
    }
}
