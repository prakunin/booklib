package org.booklore.service.migration;

import org.booklore.service.migration.migrations.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class AppMigrationStartup {

    private final AppMigrationService appMigrationService;
    private final GenerateInstallationIdMigration generateInstallationIdMigration;
    private final MigrateInstallationIdToJsonMigration migrateInstallationIdToJsonMigration;
    private final PopulateMissingFileSizesMigration populateMissingFileSizesMigration;
    private final PopulateMetadataScoresMigration populateMetadataScoresMigration;
    private final PopulateFileHashesMigration populateFileHashesMigration;
    private final PopulateCoversAndResizeThumbnailsMigration populateCoversAndResizeThumbnailsMigration;
    private final PopulateSearchTextMigration populateSearchTextMigration;
    private final GenerateCoverHashMigration generateCoverHashMigration;
    private final MigrateProgressToFileProgressMigration migrateProgressToFileProgressMigration;

    @EventListener(ApplicationReadyEvent.class)
    public void runMigrationsOnce() {
        runMigration(generateInstallationIdMigration);
        runMigration(migrateInstallationIdToJsonMigration);
        runMigration(populateMissingFileSizesMigration);
        runMigration(populateMetadataScoresMigration);
        runMigration(populateFileHashesMigration);
        runMigration(populateCoversAndResizeThumbnailsMigration);
        runMigration(populateSearchTextMigration);
        runMigration(generateCoverHashMigration);
        runMigration(migrateProgressToFileProgressMigration);
    }

    private void runMigration(Migration migration) {
        try {
            appMigrationService.executeMigration(migration);
        } catch (Exception e) {
            log.error("Migration '{}' failed during startup; continuing with remaining migrations", migration.getKey(), e);
        }
    }
}
