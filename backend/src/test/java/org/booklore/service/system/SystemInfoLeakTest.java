package org.booklore.service.system;

import org.booklore.model.dto.system.ApplicationInfo;
import org.booklore.model.dto.system.DatabaseInfo;
import org.booklore.model.dto.system.FilesystemInfo;
import org.booklore.model.dto.system.LibraryPathInfo;
import org.booklore.model.dto.system.OsInfo;
import org.booklore.model.dto.system.RuntimeInfo;
import org.booklore.model.dto.system.StorageInfo;
import org.booklore.model.dto.system.SystemInfoDto;
import org.booklore.model.dto.system.ToolsInfo;
import org.booklore.model.enums.PathStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The System tab must never expose credentials. That is guaranteed structurally — the DTO is an
 * allowlist — and this test is what keeps it that way: adding a leaky field to any of these types
 * fails here.
 */
class SystemInfoLeakTest {

    private static final String[] FORBIDDEN = {
            "jdbc:", "password", "passwd", "secret", "token", "username", "user=", "credential"
    };

    /**
     * The exact field set each response type is allowed to carry. Adding a field to any of these
     * DTOs fails {@link FieldAllowlist#everySystemDtoCarriesOnlyItsDeclaredFields()} until it is
     * added here — which is the point: a new field on this surface must be a deliberate, reviewed
     * act, not something that arrives unnoticed.
     *
     * <p>Value-based scanning alone is not enough. A field populated only in production — say
     * {@code jdbcUrl} set from {@code DatabaseMetaData.getURL()} — serialises as null in a
     * hand-built fixture and slips past a string search. This checks the shape, not the sample.
     */
    private static final Map<Class<?>, Set<String>> ALLOWED_FIELDS = Map.of(
            SystemInfoDto.class, Set.of("application", "runtime", "os", "database", "storage",
                    "filesystems", "libraryPaths", "tools"),
            ApplicationInfo.class, Set.of("version", "springBootVersion"),
            RuntimeInfo.class, Set.of("javaVersion", "javaVendor", "jvmUptimeMillis",
                    "availableProcessors", "heapUsedBytes", "heapMaxBytes"),
            OsInfo.class, Set.of("name", "version", "arch"),
            DatabaseInfo.class, Set.of("vendor", "version", "status"),
            StorageInfo.class, Set.of("diskType"),
            FilesystemInfo.class, Set.of("paths", "totalBytes", "usableBytes"),
            LibraryPathInfo.class, Set.of("path", "status"),
            ToolsInfo.class, Set.of("ffprobeVersion", "kepubifyVersion"));

    @Nested
    class FieldAllowlist {

        @Test
        void everySystemDtoCarriesOnlyItsDeclaredFields() {
            ALLOWED_FIELDS.forEach((type, allowed) -> {
                Set<String> actual = Arrays.stream(type.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(Field::getName)
                        .collect(Collectors.toSet());

                assertThat(actual)
                        .as("%s exposes a field that is not on the System tab's allowlist. "
                                + "If the new field is intentional, prove it carries no credential "
                                + "and add it to ALLOWED_FIELDS.", type.getSimpleName())
                        .isEqualTo(allowed);
            });
        }
    }

    @Test
    void serialisedResponseCarriesNoCredentials() {
        SystemInfoDto dto = SystemInfoDto.builder()
                .application(ApplicationInfo.builder().version("v1.2.3").springBootVersion("4.0.0").build())
                .runtime(RuntimeInfo.builder().javaVersion("25").javaVendor("Eclipse Adoptium")
                        .jvmUptimeMillis(1000L).availableProcessors(8)
                        .heapUsedBytes(100L).heapMaxBytes(200L).build())
                .os(OsInfo.builder().name("Linux").version("6.17.0").arch("amd64").build())
                .database(DatabaseInfo.builder().vendor("MariaDB").version("12.3.2-MariaDB").status("UP").build())
                .storage(StorageInfo.builder().diskType("LOCAL").build())
                .filesystems(List.of(FilesystemInfo.builder()
                        .paths(List.of("/app/data", "/bookdrop")).totalBytes(900L).usableBytes(524L).build()))
                .libraryPaths(List.of(LibraryPathInfo.builder()
                        .path("/books/fb2.Flibusta.Net").status(PathStatus.OK).build()))
                .tools(ToolsInfo.builder().ffprobeVersion("ffprobe version 8.1.2")
                        .kepubifyVersion("kepubify v4.0.4").build())
                .build();

        String json = new ObjectMapper().writeValueAsString(dto).toLowerCase();

        assertThat(json).doesNotContain(FORBIDDEN);
    }
}
