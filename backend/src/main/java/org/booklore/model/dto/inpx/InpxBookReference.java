package org.booklore.model.dto.inpx;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InpxBookReference {
    @NotBlank
    private String archiveName;

    @NotBlank
    private String fileName;

    @NotBlank
    private String extension;
}
