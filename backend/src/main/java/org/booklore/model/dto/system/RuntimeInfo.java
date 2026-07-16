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
    // Boxed, not primitive: a degraded block (see SystemInfoService#runtimeInfo) must be able to
    // report every numeric field as null ("could not read this"), distinct from a genuine 0
    // ("read it, and it is zero"). A primitive here would silently collapse those two states.
    private Long jvmUptimeMillis;
    private Integer availableProcessors;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
}
