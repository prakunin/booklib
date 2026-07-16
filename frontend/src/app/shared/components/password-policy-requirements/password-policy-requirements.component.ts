import {Component, input} from '@angular/core';
import {TranslocoDirective} from '@jsverse/transloco';
import {PasswordPolicy} from '../../model/app-settings.model';
import {DEFAULT_PASSWORD_POLICY} from '../../validators/password-policy.validator';

@Component({
  selector: 'app-password-policy-requirements',
  standalone: true,
  imports: [TranslocoDirective],
  template: `
    <small *transloco="let t; prefix: 'shared.passwordPolicy'" class="text-(--p-text-muted-color)">
      {{ t('minimumLength', {count: policy().minimumLength}) }}
      @if (policy().requireUppercase) { · {{ t('uppercase') }} }
      @if (policy().requireLowercase) { · {{ t('lowercase') }} }
      @if (policy().requireDigit) { · {{ t('digit') }} }
      @if (policy().requireSpecialCharacter) { · {{ t('specialCharacter') }} }
    </small>
  `,
})
export class PasswordPolicyRequirementsComponent {
  readonly policy = input<PasswordPolicy>(DEFAULT_PASSWORD_POLICY);
}
