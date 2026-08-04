package org.booklore.model.dto.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.booklore.model.enums.TaskType;
import org.booklore.task.options.LibraryRescanOptions;
import org.booklore.task.options.LocalCatalogBackfillOptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code options} is polymorphic ({@code @JsonTypeInfo} keyed on {@code taskType}), which means
 * every task type that wants to read its options has to be registered as a {@code @JsonSubTypes}
 * entry or Jackson rejects the whole request before any task code runs. Task 11d found
 * {@code LOCAL_CATALOG_BACKFILL} shipped without that registration: {@code TaskCreateRequest} tests
 * elsewhere construct the DTO directly in Java and so never exercise real deserialization, and the
 * frontend spec mocks the HTTP call. Only a round trip through a real {@link ObjectMapper} — the
 * same one Spring uses to parse the request body — would have caught it.
 */
class TaskCreateRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesLocalCatalogBackfillOptions() throws Exception {
        String json = """
                {"taskType":"LOCAL_CATALOG_BACKFILL","options":{"libraryId":19}}
                """;

        TaskCreateRequest request = objectMapper.readValue(json, TaskCreateRequest.class);

        assertThat(request.getTaskType()).isEqualTo(TaskType.LOCAL_CATALOG_BACKFILL);
        LocalCatalogBackfillOptions options = request.getOptionsAs(LocalCatalogBackfillOptions.class);
        assertThat(options).isNotNull();
        assertThat(options.getLibraryId()).isEqualTo(19L);
    }

    @Test
    void deserializesLibraryRescanOptions() throws Exception {
        String json = """
                {"taskType":"REFRESH_LIBRARY_METADATA","options":{"updateMetadataFromFiles":true,"metadataReplaceMode":"REPLACE_ALL"}}
                """;

        TaskCreateRequest request = objectMapper.readValue(json, TaskCreateRequest.class);

        assertThat(request.getTaskType()).isEqualTo(TaskType.REFRESH_LIBRARY_METADATA);
        LibraryRescanOptions options = request.getOptionsAs(LibraryRescanOptions.class);
        assertThat(options).isNotNull();
        assertThat(options.isUpdateMetadataFromFiles()).isTrue();
    }

    @Test
    void deserializesMetadataRefreshRequestOptions() throws Exception {
        String json = """
                {"taskType":"REFRESH_METADATA_MANUAL","options":{"refreshType":"LIBRARY","libraryId":5}}
                """;

        TaskCreateRequest request = objectMapper.readValue(json, TaskCreateRequest.class);

        assertThat(request.getTaskType()).isEqualTo(TaskType.REFRESH_METADATA_MANUAL);
        MetadataRefreshRequest options = request.getOptionsAs(MetadataRefreshRequest.class);
        assertThat(options).isNotNull();
        assertThat(options.getLibraryId()).isEqualTo(5L);
    }

    @Test
    void rejectsOptionsForATaskTypeThatHasNoRegisteredSubtype() {
        // CLEANUP_DELETED_BOOKS is a real TaskType, but it takes no options and has no
        // @JsonSubTypes entry. Sending options for it must fail loudly (InvalidTypeIdException),
        // exactly the way LOCAL_CATALOG_BACKFILL used to fail before it was registered — Jackson
        // must never silently fall back to an untyped Map here.
        String json = """
                {"taskType":"CLEANUP_DELETED_BOOKS","options":{"libraryId":19}}
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, TaskCreateRequest.class))
                .isInstanceOf(InvalidTypeIdException.class)
                .hasMessageContaining("CLEANUP_DELETED_BOOKS");
    }

    /**
     * The test above only pins today's known-unregistered type ({@code CLEANUP_DELETED_BOOKS}). It
     * would not notice a *new* {@link TaskType} shipping without a {@code @JsonSubTypes} entry —
     * which is exactly how {@code LOCAL_CATALOG_BACKFILL} shipped broken in the first place. This
     * suite enumerates every {@link TaskType} and requires each one to make a conscious choice:
     * either it is registered as a {@code @JsonSubTypes} entry on {@link TaskCreateRequest#options},
     * or it is named in {@link #TASK_TYPES_WITHOUT_OPTIONS} with a comment saying why it takes none.
     * Forgetting both makes this test fail with a message that says exactly what to do.
     */
    @Nested
    class OptionsRegistrationCoverage {

        /**
         * Confirmed as of this test by reading every {@code Task} implementation's
         * {@code execute}/{@code validatePermissions}: none of these call
         * {@code request.getOptions()} or {@code request.getOptionsAs(...)}, only
         * {@code getTaskId()}/{@code isTriggeredByCron()} — so they genuinely take no options today.
         */
        private static final Set<TaskType> TASK_TYPES_WITHOUT_OPTIONS = EnumSet.of(
                TaskType.UPDATE_BOOK_RECOMMENDATIONS, // BookRecommendationUpdaterTask reads no options
                TaskType.CLEANUP_DELETED_BOOKS,       // DeletedBooksCleanupTask reads no options
                TaskType.SYNC_LIBRARY_FILES,          // LibraryScanTask reads no options
                TaskType.BOOKDROP_PERIODIC_SCANNING,  // BookdropPeriodicScanTask reads no options
                TaskType.CLEANUP_TEMP_METADATA,       // TempFetchedMetadataCleanupTask reads no options
                TaskType.RECOMPUTE_FACET_COUNTS       // FacetCountRecomputeTask reads no options
        );

        @ParameterizedTest
        @EnumSource(TaskType.class)
        void isRegisteredOrExplicitlyAllowListedAsOptionless(TaskType taskType) throws NoSuchFieldException {
            Set<String> registeredTypeNames = registeredOptionsSubtypeNames();

            boolean registered = registeredTypeNames.contains(taskType.name());
            boolean allowListed = TASK_TYPES_WITHOUT_OPTIONS.contains(taskType);

            assertThat(registered || allowListed)
                    .as("TaskType.%s is neither registered as a @JsonSubTypes entry on "
                            + "TaskCreateRequest.options nor listed in "
                            + "TaskCreateRequestTest.OptionsRegistrationCoverage.TASK_TYPES_WITHOUT_OPTIONS. "
                            + "If this task type reads request options, add "
                            + "@JsonSubTypes.Type(value = <YourOptions>.class, name = \"%s\") to "
                            + "TaskCreateRequest (see LOCAL_CATALOG_BACKFILL for the pattern). If it "
                            + "genuinely takes no options, add TaskType.%s to TASK_TYPES_WITHOUT_OPTIONS "
                            + "with a comment saying why.", taskType.name(), taskType.name(), taskType.name())
                    .isTrue();

            assertThat(registered && allowListed)
                    .as("TaskType.%s is both registered as a @JsonSubTypes entry AND listed in "
                            + "TASK_TYPES_WITHOUT_OPTIONS. Remove it from the allow-list — it now has "
                            + "registered options and the allow-list entry is stale.", taskType.name())
                    .isFalse();
        }

        private Set<String> registeredOptionsSubtypeNames() throws NoSuchFieldException {
            JsonSubTypes jsonSubTypes = TaskCreateRequest.class
                    .getDeclaredField("options")
                    .getAnnotation(JsonSubTypes.class);
            assertThat(jsonSubTypes)
                    .as("TaskCreateRequest.options is expected to carry @JsonSubTypes")
                    .isNotNull();

            return Arrays.stream(jsonSubTypes.value())
                    .map(JsonSubTypes.Type::name)
                    .collect(Collectors.toSet());
        }
    }
}
