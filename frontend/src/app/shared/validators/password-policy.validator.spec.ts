import {FormControl} from '@angular/forms';
import {describe, expect, it} from 'vitest';
import {passwordPolicyValidator, passwordPolicyViolations} from './password-policy.validator';

describe('passwordPolicyValidator', () => {
  it('allows a single-character password when configured', () => {
    expect(passwordPolicyViolations('1', {
      minimumLength: 1,
      requireUppercase: false,
      requireLowercase: false,
      requireDigit: false,
      requireSpecialCharacter: false,
    })).toEqual([]);
  });

  it('reports each enabled requirement', () => {
    const policy = {
      minimumLength: 4,
      requireUppercase: true,
      requireLowercase: true,
      requireDigit: true,
      requireSpecialCharacter: true,
    };

    expect(passwordPolicyViolations('abc', policy)).toEqual([
      'minimumLength', 'uppercase', 'digit', 'specialCharacter'
    ]);
    expect(passwordPolicyViolations('Ab1!', policy)).toEqual([]);
  });

  it('returns Angular validation errors for an invalid value', () => {
    const validator = passwordPolicyValidator(() => ({
      minimumLength: 3,
      requireUppercase: false,
      requireLowercase: false,
      requireDigit: false,
      requireSpecialCharacter: false,
    }));

    expect(validator(new FormControl('1'))).toEqual({passwordPolicy: ['minimumLength']});
  });
});
