package org.booklore.service.user;

import org.booklore.exception.APIException;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.PasswordPolicy;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordPolicyServiceTest {

    @Mock
    private AppSettingService appSettingService;

    private PasswordPolicyService passwordPolicyService;

    @BeforeEach
    void setUp() {
        passwordPolicyService = new PasswordPolicyService(appSettingService);
    }

    @Test
    void validate_acceptsSingleCharacterWhenConfiguredForHomeUse() {
        usePolicy(PasswordPolicy.builder().minimumLength(1).build());

        assertThatCode(() -> passwordPolicyService.validate("1")).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsPasswordShorterThanConfiguredMinimum() {
        usePolicy(PasswordPolicy.builder().minimumLength(4).build());

        assertThatThrownBy(() -> passwordPolicyService.validate("123"))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("at least 4 characters");
    }

    @Test
    void validate_enforcesEnabledCharacterRequirements() {
        usePolicy(PasswordPolicy.builder()
                .minimumLength(1)
                .requireUppercase(true)
                .requireLowercase(true)
                .requireDigit(true)
                .requireSpecialCharacter(true)
                .build());

        assertThatThrownBy(() -> passwordPolicyService.validate("password"))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("uppercase letter")
                .hasMessageContaining("digit")
                .hasMessageContaining("special character");

        assertThatCode(() -> passwordPolicyService.validate("Password1!"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsPasswordsLongerThanBcryptLimit() {
        usePolicy(PasswordPolicy.builder().minimumLength(1).build());

        assertThatThrownBy(() -> passwordPolicyService.validate("a".repeat(73)))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("no more than 72 characters");
    }

    private void usePolicy(PasswordPolicy policy) {
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().passwordPolicy(policy).build());
    }
}
