package org.booklore.model.dto.inpx;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class InpxImportResult {
    private int imported;
    private int skipped;
    private int failed;
    private List<String> errors;
}
