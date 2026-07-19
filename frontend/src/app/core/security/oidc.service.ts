import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom, Observable} from 'rxjs';
import {API_CONFIG} from '../config/api-config';

interface OidcPkceState {
  state: string;
  nonce: string;
}

interface OidcAuthorizationState {
  state: string;
  nonce: string;
  codeChallenge: string;
  codeChallengeMethod: string;
}

interface OidcTokenResponse {
  accessToken: string;
  refreshToken: string;
  isDefaultPassword?: boolean;
  expires?: number
}

@Injectable({providedIn: 'root'})
export class OidcService {

  private readonly http = inject(HttpClient);

  buildAuthUrl(
    issuerUri: string,
    clientId: string,
    codeChallenge: string,
    state: string,
    nonce: string,
    codeChallengeMethod = 'S256',
    authorizationEndpoint?: string,
    scopes?: string
  ): Promise<string> {
    const redirectUri = `${globalThis.location.origin}/oauth2-callback`;
    const scope = scopes?.trim() || 'openid profile email groups offline_access';

    if (authorizationEndpoint) {
      return Promise.resolve(this.buildUrl(authorizationEndpoint, clientId, redirectUri, scope, codeChallenge, state, nonce, codeChallengeMethod));
    }

    // Fetch from discovery if authorization_endpoint not provided
    return fetch(`${issuerUri.replace(/\/+$/, '')}/.well-known/openid-configuration`)
      .then(res => res.json())
      .then(doc => {
        const endpoint = doc.authorization_endpoint;
        if (!endpoint) {
          throw new Error('authorization_endpoint not found in discovery document');
        }
        return this.buildUrl(endpoint, clientId, redirectUri, scope, codeChallenge, state, nonce, codeChallengeMethod);
      });
  }

  async fetchState(): Promise<OidcAuthorizationState> {
    const response = await firstValueFrom(
      this.http.get<OidcAuthorizationState>(`${API_CONFIG.BASE_URL}/api/v1/auth/oidc/state`)
    );
    return response;
  }

  exchangeCode(code: string, state: string): Observable<OidcTokenResponse> {
    const redirectUri = `${globalThis.location.origin}/oauth2-callback`;
    return this.http.post<OidcTokenResponse>(`${API_CONFIG.BASE_URL}/api/v1/auth/oidc/callback`, {
      code,
      redirectUri,
      state
    });
  }

  storePkceState(pkceState: OidcPkceState): void {
    sessionStorage.setItem(`oidc_pkce_${pkceState.state}`, JSON.stringify(pkceState));
  }

  retrievePkceState(state: string): OidcPkceState | null {
    const key = `oidc_pkce_${state}`;
    const stored = sessionStorage.getItem(key);
    sessionStorage.removeItem(key);
    if (!stored) return null;
    try {
      return JSON.parse(stored);
    } catch {
      return null;
    }
  }

  private buildUrl(
    endpoint: string,
    clientId: string,
    redirectUri: string,
    scope: string,
    codeChallenge: string,
    state: string,
    nonce: string,
    codeChallengeMethod: string
  ): string {
    const params = new URLSearchParams({
      response_type: 'code',
      client_id: clientId,
      redirect_uri: redirectUri,
      scope,
      code_challenge: codeChallenge,
      code_challenge_method: codeChallengeMethod,
      state,
      nonce,
    });
    return `${endpoint}?${params.toString()}`;
  }
}
