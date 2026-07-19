package org.booklore.service.file;

import org.booklore.config.AppProperties;
import org.booklore.exception.APIException;
import org.booklore.model.dto.inpx.InpxIndexOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathServiceTest {

    @TempDir
    private Path tempDir;

    private Path configRoot;
    private Path bookdropRoot;
    private PathService pathService;

    @BeforeEach
    void setUp() throws IOException {
        configRoot = Files.createDirectory(tempDir.resolve("config"));
        bookdropRoot = Files.createDirectory(tempDir.resolve("bookdrop"));

        AppProperties appProperties = new AppProperties();
        appProperties.setPathConfig(configRoot.toString());
        appProperties.setBookdropFolder(bookdropRoot.toString());
        pathService = new PathService(appProperties);
    }

    @Nested
    class GetFoldersAtPath {

        @Test
        void returnsOnlyDirectoriesSortedByName() throws IOException {
            Path dir = configRoot;
            Files.createDirectory(dir.resolve("b-folder"));
            Files.createDirectory(dir.resolve("a-folder"));
            Files.writeString(dir.resolve("not-a-folder.txt"), "text");

            List<String> found = pathService.getFoldersAtPath(dir.toString());

            assertThat(found).containsExactly(
                    dir.resolve("a-folder").toString(),
                    dir.resolve("b-folder").toString());
        }

        @Test
        void returnsEmptyListWhenDirectoryDoesNotExist() {
            assertThat(pathService.getFoldersAtPath(configRoot.resolve("missing").toString())).isEmpty();
        }

        @Test
        void rejectsBlankPath() {
            assertThatThrownBy(() -> pathService.getFoldersAtPath("  "))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void rejectsBlockedSystemPath() {
            assertThatThrownBy(() -> pathService.getFoldersAtPath("/proc"))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void rejectsExistingDirectoryOutsideConfiguredRoots() throws IOException {
            Path outside = Files.createDirectory(tempDir.resolve("outside"));
            String outsidePath = outside.toString();

            assertThatThrownBy(() -> pathService.getFoldersAtPath(outsidePath))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void allowsBookdropRoot() throws IOException {
            Files.createDirectory(bookdropRoot.resolve("incoming"));

            assertThat(pathService.getFoldersAtPath(bookdropRoot.toString()))
                    .containsExactly(bookdropRoot.resolve("incoming").toString());
        }
    }

    @Nested
    class GetInpxFilesAtPath {

        @Test
        void returnsOnlyInpxFilesSortedByName() throws IOException {
            Path dir = configRoot;
            Files.writeString(dir.resolve("b-catalog.inpx"), "bb");
            Files.writeString(dir.resolve("a-catalog.inpx"), "a");
            Files.writeString(dir.resolve("fb2-000.zip"), "zip");
            Files.createDirectory(dir.resolve("nested.inpx"));

            List<InpxIndexOption> found = pathService.getInpxFilesAtPath(dir.toString());

            assertThat(found).extracting(InpxIndexOption::fileName)
                    .containsExactly("a-catalog.inpx", "b-catalog.inpx");
            assertThat(found.getFirst().path()).isEqualTo(dir.resolve("a-catalog.inpx").toString());
            assertThat(found.getFirst().sizeBytes()).isEqualTo(1L);
            assertThat(found.get(1).sizeBytes()).isEqualTo(2L);
        }

        @Test
        void matchesExtensionCaseInsensitively() throws IOException {
            Path dir = configRoot;
            Files.writeString(dir.resolve("catalog.INPX"), "x");

            assertThat(pathService.getInpxFilesAtPath(dir.toString()))
                    .extracting(InpxIndexOption::fileName)
                    .containsExactly("catalog.INPX");
        }

        @Test
        void excludesAppleDoubleSidecarsAndHiddenDotfiles() throws IOException {
            Path dir = configRoot;
            Files.writeString(dir.resolve("catalog.inpx"), "catalog");
            Files.writeString(dir.resolve("._catalog.inpx"), "resource-fork");
            Files.writeString(dir.resolve(".hidden.inpx"), "hidden");

            List<InpxIndexOption> found = pathService.getInpxFilesAtPath(dir.toString());

            assertThat(found).extracting(InpxIndexOption::fileName)
                    .containsExactly("catalog.inpx");
        }

        @Test
        void returnsEmptyListWhenDirectoryHasNoIndexes() throws IOException {
            Path dir = configRoot;
            Files.writeString(dir.resolve("fb2-000.zip"), "zip");

            assertThat(pathService.getInpxFilesAtPath(dir.toString())).isEmpty();
        }

        @Test
        void returnsEmptyListWhenDirectoryDoesNotExist() {
            assertThat(pathService.getInpxFilesAtPath(configRoot.resolve("missing").toString())).isEmpty();
        }

        @Test
        void rejectsBlankPath() {
            assertThatThrownBy(() -> pathService.getInpxFilesAtPath("  "))
                    .isInstanceOf(APIException.class);
        }

        @Test
        void rejectsBlockedSystemPath() {
            assertThatThrownBy(() -> pathService.getInpxFilesAtPath("/proc"))
                    .isInstanceOf(APIException.class);
        }
    }
}
