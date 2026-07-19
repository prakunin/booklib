import {Component, inject, signal} from '@angular/core';
import {FormBuilder, FormGroup, ReactiveFormsModule, Validators} from '@angular/forms';
import {Router} from '@angular/router';
import {SetupService} from './setup.service';
import {InputText} from 'primeng/inputtext';
import {Button} from 'primeng/button';
import {Message} from 'primeng/message';
import {passwordMatchValidator} from '../../validators/password-match.validator';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {AppSettingsService} from '../../service/app-settings.service';
import {PasswordPolicyRequirementsComponent} from '../password-policy-requirements/password-policy-requirements.component';
import {DEFAULT_PASSWORD_POLICY, passwordPolicyValidator} from '../../validators/password-policy.validator';

@Component({
  selector: 'app-setup',
  templateUrl: './setup.component.html',
  styleUrls: ['./setup.component.scss'],
  standalone: true,
  imports: [
    ReactiveFormsModule,
    InputText,
    Button,
    Message,
    TranslocoDirective,
    PasswordPolicyRequirementsComponent
  ]
})
export class SetupComponent {
  private readonly fb = inject(FormBuilder);
  private readonly setupService = inject(SetupService);
  private readonly router = inject(Router);
  setupForm: FormGroup;
  loading = signal(false);
  error: string | null = null;
  success = false;
  private readonly t = inject(TranslocoService);
  private readonly appSettingsService = inject(AppSettingsService);

  get passwordPolicy() {
    return this.appSettingsService.publicAppSettings()?.passwordPolicy ?? DEFAULT_PASSWORD_POLICY;
  }

  constructor() {
    this.setupForm = this.fb.group({
      name: ['', [Validators.required]],
      username: ['', [Validators.required]],
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, passwordPolicyValidator(() => this.passwordPolicy)]],
      confirmPassword: ['', [Validators.required]],
    }, {validators: [passwordMatchValidator('password', 'confirmPassword')]});
  }

  onSubmit(): void {
    if (this.setupForm.invalid) return;

    this.loading.set(true);
    this.error = null;
    // Remove confirm password from the payload, as it does not need to be sent to backend
    const { confirmPassword, ...payload } = this.setupForm.value;
    this.setupService.createAdmin(payload).subscribe({
      next: () => {
        this.success = true;
        setTimeout(() => this.router.navigate(['/login']), 1500);
      },
      error: (err) => {
        this.loading.set(false);
        this.error =
          err?.error?.message || this.t.translate('shared.setup.toast.createFailedDefault');
      },
    });
  }
}
