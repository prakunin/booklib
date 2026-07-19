package org.booklore.service.migration;

import org.booklore.model.entity.AppMigrationEntity;
import org.booklore.repository.AppMigrationRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@AllArgsConstructor
@Service
public class AppMigrationService {

    private final AppMigrationRepository migrationRepository;
    private final TransactionTemplate transactionTemplate;

    public void executeMigration(Migration migration) {
        if (migration.isTransactional()) {
            transactionTemplate.executeWithoutResult(status -> executeMigrationInTransaction(migration));
            return;
        }

        if (migrationRepository.existsById(migration.getKey())) {
            log.debug("Migration '{}' already executed, skipping", migration.getKey());
            return;
        }
        try {
            migration.execute();
            transactionTemplate.executeWithoutResult(status -> saveMigrationRecord(migration));

            log.info("Migration '{}' completed successfully", migration.getKey());
        } catch (Exception e) {
            log.error("Migration '{}' failed", migration.getKey(), e);
            throw e;
        }
    }

    private void executeMigrationInTransaction(Migration migration) {
        if (migrationRepository.existsById(migration.getKey())) {
            log.debug("Migration '{}' already executed, skipping", migration.getKey());
            return;
        }
        try {
            migration.execute();
            saveMigrationRecord(migration);

            log.info("Migration '{}' completed successfully", migration.getKey());
        } catch (Exception e) {
            log.error("Migration '{}' failed", migration.getKey(), e);
            throw e;
        }
    }

    private void saveMigrationRecord(Migration migration) {
        AppMigrationEntity entity = new AppMigrationEntity(migration.getKey(), LocalDateTime.now(), migration.getDescription());
        migrationRepository.save(entity);
    }
}
