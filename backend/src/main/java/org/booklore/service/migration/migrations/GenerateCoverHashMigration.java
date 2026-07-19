package org.booklore.service.migration.migrations;

import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.migration.Migration;
import org.booklore.util.BookCoverUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateCoverHashMigration implements Migration {

    private final BookRepository bookRepository;

    @Override
    public String getKey() {
        return "generateCoverHash";
    }

    @Override
    public String getDescription() {
        return "Generate unique cover hash for all books using BookCoverUtils";
    }

    @Override
    public void execute() {
        log.info("Starting migration: {}", getKey());

        int batchSize = 1000;
        int processedCount = 0;
        long lastId = 0;

        boolean moreBatches = true;
        while (moreBatches) {
            List<BookEntity> bookBatch = bookRepository.findBooksForMigrationBatch(lastId, PageRequest.of(0, batchSize));
            if (bookBatch.isEmpty()) {
                moreBatches = false;
            } else {
                for (BookEntity book : bookBatch) {
                    if (book.getBookCoverHash() == null) {
                        book.setBookCoverHash(BookCoverUtils.generateCoverHash());
                    }
                }

                bookRepository.saveAll(bookBatch);
                processedCount += bookBatch.size();
                lastId = bookBatch.getLast().getId();

                log.info("Migration progress: {} books processed", processedCount);

                moreBatches = bookBatch.size() >= batchSize;
            }
        }

        log.info("Completed migration '{}'. Total books processed: {}", getKey(), processedCount);
    }
}

