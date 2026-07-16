package org.booklore.model.dto.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.booklore.model.enums.PathStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LibraryPathInfo {
    private String path;
    private PathStatus status;
}
