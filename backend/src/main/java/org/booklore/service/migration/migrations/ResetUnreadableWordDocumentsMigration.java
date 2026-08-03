package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.migration.Migration;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResetUnreadableWordDocumentsMigration implements Migration {

    private static final int BATCH_SIZE = 100;
    private static final long MAX_INSPECTION_BYTES = 64L * 1024 * 1024;
    private static final int LEGACY_WORD_FIB_SIGNATURE = 0xA5DC;
    private static final int WORD_FIB_SIGNATURE = 0xA5EC;
    private static final int WORD_97_FIB_VERSION = 106;

    private final BookFileRepository bookFileRepository;
    private final ArchivedBookContentService archivedBookContentService;

    @Override
    public String getKey() {
        return "resetUnreadableWordDocumentsForWord6Support";
    }

    @Override
    public String getDescription() {
        return "Retry Word documents rejected before Word 6 and Word 95 support";
    }

    @Override
    public void execute() {
        long lastId = 0;
        long reset = 0;
        while (true) {
            List<BookFileEntity> candidates = bookFileRepository.findUnreadableLegacyWordCandidatesAfterId(
                    lastId, PageRequest.of(0, BATCH_SIZE));
            if (candidates.isEmpty()) {
                break;
            }
            lastId = candidates.getLast().getId();
            List<BookFileEntity> legacyDocuments = candidates.stream()
                    .filter(this::isLegacyWordDocument)
                    .toList();
            legacyDocuments.forEach(file -> file.setDocumentParseStatus(null));
            bookFileRepository.saveAll(legacyDocuments);
            reset += legacyDocuments.size();
        }
        log.info("Migration '{}' completed. Reset {} unreadable Word document verdicts.", getKey(), reset);
    }

    private boolean isLegacyWordDocument(BookFileEntity bookFile) {
        try {
            Path path = bookFile.isArchivedSource()
                    ? archivedBookContentService.resolve(bookFile)
                    : bookFile.getFullFilePath();
            if (Files.size(path) > MAX_INSPECTION_BYTES) {
                return false;
            }
            try (POIFSFileSystem fileSystem = new POIFSFileSystem(path.toFile(), true);
                 DocumentInputStream wordDocument = fileSystem.createDocumentInputStream("WordDocument")) {
                byte[] fibBase = wordDocument.readNBytes(4);
                if (fibBase.length != 4) {
                    return false;
                }
                int signature = LittleEndian.getUShort(fibBase, 0);
                return (signature == LEGACY_WORD_FIB_SIGNATURE || signature == WORD_FIB_SIGNATURE)
                        && LittleEndian.getUShort(fibBase, 2) < WORD_97_FIB_VERSION;
            }
        } catch (Exception e) {
            log.debug("Unable to inspect unreadable Word document {}", bookFile.getId(), e);
            return false;
        }
    }
}
