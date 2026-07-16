package org.booklore.service.user;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.PasswordPolicy;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    private final AppSettingService appSettingService;

    public void validate(String password) {
        PasswordPolicy policy = appSettingService.getAppSettings().getPasswordPolicy();
        List<String> violations = getViolations(password, policy);
        if (!violations.isEmpty()) {
            throw ApiError.INVALID_INPUT.createException("Password " + String.join(", ", violations));
        }
    }

    List<String> getViolations(String password, PasswordPolicy policy) {
        List<String> violations = new ArrayList<>();
        if (password == null || password.length() < policy.getMinimumLength()) {
            violations.add("must be at least " + policy.getMinimumLength() + " characters long");
        }
        if (password != null && password.length() > PasswordPolicy.MAX_PASSWORD_LENGTH) {
            violations.add("must be no more than " + PasswordPolicy.MAX_PASSWORD_LENGTH + " characters long");
        }
        if (policy.isRequireUppercase() && (password == null || password.chars().noneMatch(Character::isUpperCase))) {
            violations.add("must contain an uppercase letter");
        }
        if (policy.isRequireLowercase() && (password == null || password.chars().noneMatch(Character::isLowerCase))) {
            violations.add("must contain a lowercase letter");
        }
        if (policy.isRequireDigit() && (password == null || password.chars().noneMatch(Character::isDigit))) {
            violations.add("must contain a digit");
        }
        if (policy.isRequireSpecialCharacter() && (password == null || password.chars().noneMatch(c -> !Character.isLetterOrDigit(c)))) {
            violations.add("must contain a special character");
        }
        return violations;
    }
}
