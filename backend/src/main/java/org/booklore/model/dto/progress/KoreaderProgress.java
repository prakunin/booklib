package org.booklore.model.dto.progress;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class KoreaderProgress {
    private Long timestamp;
    private String document;
    private Float percentage;
    private String progress;
    private String device;
    @JsonProperty("device_id") // KOReader Sync protocol wire field name; kept stable while Java field follows naming conventions
    private String deviceId;
}
