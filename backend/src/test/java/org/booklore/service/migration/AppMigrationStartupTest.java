package org.booklore.service.migration;

import org.booklore.service.migration.migrations.GenerateCoverHashMigration;
import org.booklore.service.migration.migrations.GenerateInstallationIdMigration;
import org.booklore.service.migration.migrations.MigrateInstallationIdToJsonMigration;
import org.booklore.service.migration.migrations.MigrateProgressToFileProgressMigration;
import org.booklore.service.migration.migrations.PopulateCoversAndResizeThumbnailsMigration;
import org.booklore.service.migration.migrations.PopulateFileHashesMigration;
import org.booklore.service.migration.migrations.PopulateMetadataScoresMigration;
import org.booklore.service.migration.migrations.PopulateMissingFileSizesMigration;
import org.booklore.service.migration.migrations.PopulateSearchTextMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AppMigrationStartupTest {

    @Mock private AppMigrationService appMigrationService;
    @Mock private GenerateInstallationIdMigration generateInstallationIdMigration;
    @Mock private MigrateInstallationIdToJsonMigration migrateInstallationIdToJsonMigration;
    @Mock private PopulateMissingFileSizesMigration populateMissingFileSizesMigration;
    @Mock private PopulateMetadataScoresMigration populateMetadataScoresMigration;
    @Mock private PopulateFileHashesMigration populateFileHashesMigration;
    @Mock private PopulateCoversAndResizeThumbnailsMigration populateCoversAndResizeThumbnailsMigration;
    @Mock private PopulateSearchTextMigration populateSearchTextMigration;
    @Mock private GenerateCoverHashMigration generateCoverHashMigration;
    @Mock private MigrateProgressToFileProgressMigration migrateProgressToFileProgressMigration;

    private AppMigrationStartup startup;

    @BeforeEach
    void setUp() {
        startup = new AppMigrationStartup(
                appMigrationService,
                generateInstallationIdMigration,
                migrateInstallationIdToJsonMigration,
                populateMissingFileSizesMigration,
                populateMetadataScoresMigration,
                populateFileHashesMigration,
                populateCoversAndResizeThumbnailsMigration,
                populateSearchTextMigration,
                generateCoverHashMigration,
                migrateProgressToFileProgressMigration
        );
    }

    @Test
    void runMigrationsOnceContinuesAfterMigrationFailure() {
        doThrow(new RuntimeException("bad row"))
                .when(appMigrationService)
                .executeMigration(populateMissingFileSizesMigration);

        assertThatCode(() -> startup.runMigrationsOnce()).doesNotThrowAnyException();

        InOrder inOrder = inOrder(appMigrationService);
        inOrder.verify(appMigrationService).executeMigration(generateInstallationIdMigration);
        inOrder.verify(appMigrationService).executeMigration(migrateInstallationIdToJsonMigration);
        inOrder.verify(appMigrationService).executeMigration(populateMissingFileSizesMigration);
        inOrder.verify(appMigrationService).executeMigration(populateMetadataScoresMigration);
        inOrder.verify(appMigrationService).executeMigration(populateFileHashesMigration);
        inOrder.verify(appMigrationService).executeMigration(populateCoversAndResizeThumbnailsMigration);
        inOrder.verify(appMigrationService).executeMigration(populateSearchTextMigration);
        inOrder.verify(appMigrationService).executeMigration(generateCoverHashMigration);
        inOrder.verify(appMigrationService).executeMigration(migrateProgressToFileProgressMigration);
    }
}
