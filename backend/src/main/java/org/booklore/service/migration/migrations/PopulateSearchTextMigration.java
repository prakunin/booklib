package org.booklore.service.migration.migrations;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.migration.Migration;
import org.booklore.util.BookUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PopulateSearchTextMigration implements Migration {

    private final BookRepository bookRepository;

    @Override
    public String getKey() {
        return "populateSearchText";
    }

    @Override
    public String getDescription() {
        return "Populate search_text column for all books";
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
                processedCount += processBatch(bookBatch);
                lastId = bookBatch.getLast().getId();

                log.info("Migration progress: {} books processed", processedCount);

                moreBatches = bookBatch.size() >= batchSize;
            }
        }

        log.info("Completed migration '{}'. Total books processed: {}", getKey(), processedCount);
    }

    private int processBatch(List<BookEntity> bookBatch) {
        List<Long> bookIds = bookBatch.stream().map(BookEntity::getId).toList();
        List<BookEntity> books = bookRepository.findBooksWithMetadataAndAuthors(bookIds);

        for (BookEntity book : books) {
            updateSearchTextQuietly(book);
        }

        bookRepository.saveAll(books);
        return books.size();
    }

    private void updateSearchTextQuietly(BookEntity book) {
        BookMetadataEntity m = book.getMetadata();
        if (m != null) {
            try {
                m.setSearchText(BookUtils.buildSearchText(m));
            } catch (Exception ex) {
                log.warn("Failed to build search text for book {}: {}", book.getId(), ex.getMessage());
            }
        }
    }
}

