package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.migration.Migration;
import org.booklore.service.migration.MigrationIncompleteException;
import org.booklore.util.ArchiveUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Component
@RequiredArgsConstructor
public class PopulateArchiveFileSizesMigration implements Migration {

    private static final int BATCH_SIZE = 500;

    private final BookFileRepository bookFileRepository;
    private final PlatformTransactionManager transactionManager;

    @Override
    public String getKey() {
        return "populateArchiveFileSizes";
    }

    @Override
    public String getDescription() {
        return "Populate file sizes for archived book entries";
    }

    @Override
    public boolean runsInSingleTransaction() {
        return false;
    }

    @Override
    public void execute() {
        log.info("Starting migration: {} for archived book files.", getKey());

        long lastId = 0;
        long scanned = 0;
        long updated = 0;
        long unresolved = 0;

        while (true) {
            List<BookFileEntity> files = bookFileRepository.findArchivedBookFilesMissingSizeAfterId(
                    lastId, PageRequest.of(0, BATCH_SIZE));
            if (files.isEmpty()) {
                break;
            }

            lastId = files.get(files.size() - 1).getId();
            scanned += files.size();

            BatchResult result = populateBatch(files);
            saveBatch(result.updatedFiles());
            updated += result.updatedFiles().size();
            unresolved += result.unresolvedCount();
        }

        if (unresolved > 0) {
            throw new MigrationIncompleteException("Unable to resolve file sizes for " + unresolved + " archived book files");
        }

        log.info("Migration '{}' completed. Scanned: {}, updated: {} archived book files.",
                getKey(), scanned, updated);
    }

    private BatchResult populateBatch(List<BookFileEntity> files) {
        List<BookFileEntity> updatedFiles = new ArrayList<>();
        List<BookFileEntity> unresolvedFiles = new ArrayList<>();
        Map<Path, List<BookFileEntity>> filesByArchive = new HashMap<>();
        for (BookFileEntity file : files) {
            Path archivePath = resolveArchivePath(file);
            if (archivePath != null) {
                filesByArchive.computeIfAbsent(archivePath, ignored -> new ArrayList<>()).add(file);
            } else {
                unresolvedFiles.add(file);
            }
        }

        filesByArchive.forEach((archivePath, archiveFiles) -> populateFromArchive(archivePath, archiveFiles, updatedFiles, unresolvedFiles));
        return new BatchResult(updatedFiles, unresolvedFiles.size());
    }

    private void populateFromArchive(Path archivePath, List<BookFileEntity> files, List<BookFileEntity> updatedFiles,
                                     List<BookFileEntity> unresolvedFiles) {
        if (ArchiveUtils.detectArchiveTypeByExtension(archivePath.getFileName().toString()) != ArchiveUtils.ArchiveType.ZIP) {
            unresolvedFiles.addAll(files);
            return;
        }

        if (!Files.isRegularFile(archivePath)) {
            log.debug("Skipping missing archive while populating file sizes: {}", archivePath);
            unresolvedFiles.addAll(files);
            return;
        }

        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            for (BookFileEntity file : files) {
                ZipEntry entry = archive.getEntry(file.getSourceArchiveEntry());
                if (entry != null && entry.getSize() >= 0) {
                    file.setFileSizeKb(entry.getSize() / 1024);
                    updatedFiles.add(file);
                } else {
                    unresolvedFiles.add(file);
                }
            }
        } catch (IOException e) {
            log.warn("Unable to read archive file sizes from {}: {}", archivePath, e.getMessage());
            unresolvedFiles.addAll(files);
        }
    }

    private Path resolveArchivePath(BookFileEntity file) {
        try {
            if (file.getBook() == null) {
                return null;
            }

            String archiveRoot = file.getBook().getLibrary() == null ? null : file.getBook().getLibrary().getInpxArchivePath();
            if (archiveRoot == null || archiveRoot.isBlank()) {
                archiveRoot = file.getBook().getLibraryPath() == null ? null : file.getBook().getLibraryPath().getPath();
            }
            if (archiveRoot == null || archiveRoot.isBlank()) {
                return null;
            }

            Path libraryRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
            Path fileSubPath = blankToEmptyPath(file.getFileSubPath());
            Path sourceArchive = Path.of(file.getSourceArchive());

            if (fileSubPath.isAbsolute() || sourceArchive.isAbsolute()) {
                return null;
            }

            Path archivePath = libraryRoot.resolve(fileSubPath).resolve(sourceArchive).normalize();
            if (!archivePath.startsWith(libraryRoot)) {
                return null;
            }
            return archivePath;
        } catch (InvalidPathException e) {
            log.debug("Skipping archived file with invalid path data: {}", e.getMessage());
            return null;
        }
    }

    private Path blankToEmptyPath(String value) {
        return Path.of(Objects.requireNonNullElse(value, ""));
    }

    private void saveBatch(List<BookFileEntity> updatedFiles) {
        if (updatedFiles.isEmpty()) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bookFileRepository.saveAll(updatedFiles);
            bookFileRepository.flush();
        });
    }

    private record BatchResult(List<BookFileEntity> updatedFiles, long unresolvedCount) {
    }
}
