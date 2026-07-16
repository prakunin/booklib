package org.booklore.model.dto.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilesystemInfo {
    private List<String> paths;
    private long totalBytes;
    private long usableBytes;
}
