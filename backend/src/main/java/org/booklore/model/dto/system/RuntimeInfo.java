package org.booklore.model.dto.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeInfo {
    private String javaVersion;
    private String javaVendor;
    private long jvmUptimeMillis;
    private int availableProcessors;
    private long heapUsedBytes;
    private long heapMaxBytes;
}
