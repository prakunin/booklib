package org.booklore.model.dto.inpx;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InpxArchiveRescanRequest {
    @NotBlank
    private String archiveName;
}
