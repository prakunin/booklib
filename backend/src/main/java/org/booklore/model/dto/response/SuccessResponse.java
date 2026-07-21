package org.booklore.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuccessResponse<T> {

    private final int status;
    private final String message;
    private final T data;
    private final LocalDateTime timestamp;

    public SuccessResponse(int status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now(ZoneId.systemDefault());
    }

    public SuccessResponse(int status, String message) {
        this(status, message, null);
    }
}
