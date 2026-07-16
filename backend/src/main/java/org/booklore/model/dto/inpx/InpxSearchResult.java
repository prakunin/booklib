package org.booklore.model.dto.inpx;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class InpxSearchResult {
    private List<InpxBookDto> books;
    private long scannedCount;
    private boolean truncated;
}
