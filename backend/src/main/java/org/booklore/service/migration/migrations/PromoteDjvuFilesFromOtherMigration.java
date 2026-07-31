package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.migration.Migration;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Moves already-catalogued DjVu files off the download-only {@code OTHER} type.
 * <p>
 * Before DjVu had a reader, every {@code .djvu} reached the catalog through INPX archive ingest and
 * was stored as {@code OTHER}: listed and downloadable, with nothing to open. Those rows keep that
 * type until something rewrites them, so without this migration a library full of scanned books
 * sees no benefit from DjVu support at all - only files added afterwards would be readable.
 * <p>
 * Deliberately narrow: it only touches rows whose file name ends in {@code .djvu} or {@code .djv}.
 * Other {@code OTHER} formats - rtf and the rest - still have no reader and are left alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromoteDjvuFilesFromOtherMigration implements Migration {

    private static final int BATCH_SIZE = 500;

    private final BookFileRepository bookFileRepository;
    private final PlatformTransactionManager transactionManager;

    @Override
    public String getKey() {
        return "promoteDjvuFilesFromOther";
    }

    @Override
    public String getDescription() {
        return "Give already-catalogued DjVu files the readable DJVU type instead of OTHER";
    }

    @Override
    public boolean runsInSingleTransaction() {
        return false;
    }

    @Override
    public void execute() {
        log.info("Starting migration: {}", getKey());

        long lastId = 0;
        long promoted = 0;

        while (true) {
            List<BookFileEntity> files = bookFileRepository.findDownloadOnlyDjvuFilesAfterId(
                    lastId, PageRequest.of(0, BATCH_SIZE));
            if (files.isEmpty()) {
                break;
            }

            lastId = files.getLast().getId();
            files.forEach(file -> file.setBookType(BookFileType.DJVU));
            saveBatch(files);
            promoted += files.size();
        }

        log.info("Migration '{}' completed. Promoted {} DjVu files from OTHER to DJVU.", getKey(), promoted);
    }

    private void saveBatch(List<BookFileEntity> files) {
        new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
            bookFileRepository.saveAll(files);
            bookFileRepository.flush();
        });
    }
}
