import {AbstractControl, ValidationErrors, ValidatorFn} from '@angular/forms';
import {PasswordPolicy} from '../model/app-settings.model';

export const DEFAULT_PASSWORD_POLICY: PasswordPolicy = {
  minimumLength: 8,
  requireUppercase: false,
  requireLowercase: false,
  requireDigit: false,
  requireSpecialCharacter: false,
};

export function passwordPolicyViolations(password: string, policy: PasswordPolicy): string[] {
  const violations: string[] = [];
  if (password.length < policy.minimumLength) violations.push('minimumLength');
  if (password.length > 72) violations.push('maximumLength');
  if (policy.requireUppercase && !/\p{Lu}/u.test(password)) violations.push('uppercase');
  if (policy.requireLowercase && !/\p{Ll}/u.test(password)) violations.push('lowercase');
  if (policy.requireDigit && !/\p{Nd}/u.test(password)) violations.push('digit');
  if (policy.requireSpecialCharacter && !/[^\p{L}\p{N}]/u.test(password)) violations.push('specialCharacter');
  return violations;
}

export function passwordPolicyValidator(getPolicy: () => PasswordPolicy): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    if (!control.value) return null;
    const violations = passwordPolicyViolations(String(control.value), getPolicy());
    return violations.length === 0 ? null : {passwordPolicy: violations};
  };
}
