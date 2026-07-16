package org.booklore.model.dto.inpx;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class InpxImportRequest {
    @NotBlank
    private String inpxPath;

    private String archivePath;

    @NotNull
    private Long libraryId;

    @NotNull
    private Long libraryPathId;

    @Valid
    @NotEmpty
    private List<InpxBookReference> books;
}
