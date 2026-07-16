package org.booklore.model.dto.inpx;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class InpxImportRequest {
    /**
     * Registered INPX library to read the index from. Preferred over {@link #inpxPath}, which is
     * administrator-only. Exactly one of the two must be set.
     */
    private Long sourceLibraryId;

    private String inpxPath;

    private String archivePath;

    @NotNull
    private Long libraryPathId;

    @Valid
    @NotEmpty
    private List<InpxBookReference> books;
}
