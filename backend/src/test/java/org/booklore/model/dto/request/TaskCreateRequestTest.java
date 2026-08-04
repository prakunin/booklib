package org.booklore.model.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.booklore.model.enums.TaskType;
import org.booklore.task.options.LibraryRescanOptions;
import org.booklore.task.options.LocalCatalogBackfillOptions;
import org.junit.jupiter.api.Test;

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
}
