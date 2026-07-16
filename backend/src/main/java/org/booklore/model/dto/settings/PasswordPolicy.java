package org.booklore.model.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PasswordPolicy {
    public static final int MAX_PASSWORD_LENGTH = 72;

    @Builder.Default
    private int minimumLength = 8;
    @Builder.Default
    private boolean requireUppercase = false;
    @Builder.Default
    private boolean requireLowercase = false;
    @Builder.Default
    private boolean requireDigit = false;
    @Builder.Default
    private boolean requireSpecialCharacter = false;
}
