package org.booklore.service.file;

import org.booklore.exception.APIException;
import org.booklore.model.dto.inpx.InpxIndexOption;
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

    private final PathService pathService = new PathService();

    @Nested
    class GetFoldersAtPath {

        @Test
        void returnsOnlyDirectoriesSortedByName(@TempDir Path dir) throws IOException {
            Files.createDirectory(dir.resolve("b-folder"));
            Files.createDirectory(dir.resolve("a-folder"));
            Files.writeString(dir.resolve("not-a-folder.txt"), "text");

            List<String> found = pathService.getFoldersAtPath(dir.toString());

            assertThat(found).containsExactly(
                    dir.resolve("a-folder").toString(),
                    dir.resolve("b-folder").toString());
        }

        @Test
        void returnsEmptyListWhenDirectoryDoesNotExist(@TempDir Path dir) {
            assertThat(pathService.getFoldersAtPath(dir.resolve("missing").toString())).isEmpty();
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
    }

    @Nested
    class GetInpxFilesAtPath {

        @Test
        void returnsOnlyInpxFilesSortedByName(@TempDir Path dir) throws IOException {
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
        void matchesExtensionCaseInsensitively(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("catalog.INPX"), "x");

            assertThat(pathService.getInpxFilesAtPath(dir.toString()))
                    .extracting(InpxIndexOption::fileName)
                    .containsExactly("catalog.INPX");
        }

        @Test
        void excludesAppleDoubleSidecarsAndHiddenDotfiles(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("catalog.inpx"), "catalog");
            Files.writeString(dir.resolve("._catalog.inpx"), "resource-fork");
            Files.writeString(dir.resolve(".hidden.inpx"), "hidden");

            List<InpxIndexOption> found = pathService.getInpxFilesAtPath(dir.toString());

            assertThat(found).extracting(InpxIndexOption::fileName)
                    .containsExactly("catalog.inpx");
        }

        @Test
        void returnsEmptyListWhenDirectoryHasNoIndexes(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("fb2-000.zip"), "zip");

            assertThat(pathService.getInpxFilesAtPath(dir.toString())).isEmpty();
        }

        @Test
        void returnsEmptyListWhenDirectoryDoesNotExist(@TempDir Path dir) {
            assertThat(pathService.getInpxFilesAtPath(dir.resolve("missing").toString())).isEmpty();
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
