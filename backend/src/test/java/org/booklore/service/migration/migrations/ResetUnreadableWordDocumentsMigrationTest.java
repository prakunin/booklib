package org.booklore.service.migration.migrations;

import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.DocumentParseStatus;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResetUnreadableWordDocumentsMigrationTest {

    @Mock
    private BookFileRepository bookFileRepository;

    @Mock
    private ArchivedBookContentService archivedBookContentService;

    @Test
    void resetsOnlyDocumentsThatPoiIdentifiesAsLegacyWord(@TempDir Path tempDir) throws Exception {
        BookFileEntity legacy = BookFileEntity.builder()
                .id(1L)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry("legacy.doc")
                .documentParseStatus(DocumentParseStatus.UNREADABLE)
                .build();
        BookFileEntity corrupt = BookFileEntity.builder()
                .id(2L)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry("corrupt.doc")
                .documentParseStatus(DocumentParseStatus.UNREADABLE)
                .build();
        Path legacyPath = tempDir.resolve("legacy.doc");
        String fixture = Files.readString(Path.of(
                        "src/test/resources/document/apache-poi-word6.doc.base64"))
                .lines()
                .filter(line -> !line.startsWith("#"))
                .collect(java.util.stream.Collectors.joining());
        Files.write(legacyPath, Base64.getDecoder().decode(fixture));
        Path corruptPath = tempDir.resolve("corrupt.doc");
        Files.writeString(corruptPath, "not an OLE document");
        when(bookFileRepository.findUnreadableLegacyWordCandidatesAfterId(
                eq(0L), any())).thenReturn(List.of(legacy, corrupt));
        when(bookFileRepository.findUnreadableLegacyWordCandidatesAfterId(
                eq(2L), any())).thenReturn(List.of());
        when(archivedBookContentService.resolve(legacy)).thenReturn(legacyPath);
        when(archivedBookContentService.resolve(corrupt)).thenReturn(corruptPath);
        ResetUnreadableWordDocumentsMigration migration =
                new ResetUnreadableWordDocumentsMigration(bookFileRepository, archivedBookContentService);

        migration.execute();

        verify(bookFileRepository).saveAll(List.of(legacy));
        assertThat(legacy.getDocumentParseStatus()).isNull();
        assertThat(corrupt.getDocumentParseStatus()).isEqualTo(DocumentParseStatus.UNREADABLE);
        assertThat(migration.getKey()).isEqualTo("resetUnreadableWordDocumentsForWord6Support");
    }
}
