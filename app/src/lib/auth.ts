const SESSION_KEY = 'pneumatik.session'
/** pre-3.1 key: a bare access token with no expiry or refresh token */
const LEGACY_TOKEN_KEY = 'pneumatik.token'

/**
 * Renew this many milliseconds before the access token actually expires, so
 * a request never leaves with a token that dies in flight.
 */
const RENEW_SKEW_MS = 60_000

export interface Session {
  accessToken: string
  refreshToken: string | null
  /** epoch milliseconds; null when the server did not say */
  expiresAt: number | null
  username: string | null
}

type Listener = () => void

const listeners = new Set<Listener>()
let cached: Session | null | undefined
let inFlightRenewal: Promise<boolean> | null = null
let onExpired: ((from: string) => void) | null = null

function notify(): void {
  cached = undefined
  for (const listener of listeners) {
    listener()
  }
}

export function getSession(): Session | null {
  if (cached !== undefined) return cached

  const raw = localStorage.getItem(SESSION_KEY)
  if (raw) {
    try {
      cached = JSON.parse(raw) as Session
      return cached
    } catch {
      localStorage.removeItem(SESSION_KEY)
    }
  }

  // a session from before refresh tokens existed: usable until it expires,
  // then the user signs in once more and gets a renewable one
  const legacy = localStorage.getItem(LEGACY_TOKEN_KEY)
  cached = legacy ? { accessToken: legacy, refreshToken: null, expiresAt: null, username: null } : null
  return cached
}

export function setSession(session: Session): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session))
  localStorage.removeItem(LEGACY_TOKEN_KEY)
  notify()
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY)
  localStorage.removeItem(LEGACY_TOKEN_KEY)
  notify()
}

/** Builds a session from a login or refresh response. */
export function sessionFromResponse(body: {
  access_token?: string
  refresh_token?: string
  expires_in?: number
  username?: string
}): Session | null {
  if (!body.access_token) return null
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token ?? null,
    expiresAt: body.expires_in ? Date.now() + body.expires_in * 1000 : null,
    username: body.username ?? null,
  }
}

export function getToken(): string | null {
  return getSession()?.accessToken ?? null
}

export function isAuthenticated(): boolean {
  return getSession() !== null
}

function isExpiring(session: Session): boolean {
  return session.expiresAt !== null && session.expiresAt - RENEW_SKEW_MS <= Date.now()
}

/**
 * Renews the access token when it is about to expire.
 *
 * Checked before each request rather than on a timer, so a tab that was
 * asleep for hours renews on its next request instead of bouncing the user
 * to the login page with a half-filled form open.
 *
 * @returns whether a usable access token exists afterwards
 */
export async function ensureFreshSession(): Promise<boolean> {
  const session = getSession()
  if (!session) return false
  if (!isExpiring(session)) return true
  if (!session.refreshToken) return false

  // concurrent requests share one renewal; two would rotate each other out
  inFlightRenewal ??= renew(session.refreshToken).finally(() => {
    inFlightRenewal = null
  })
  return inFlightRenewal
}

async function renew(refreshToken: string): Promise<boolean> {
  try {
    const response = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refreshToken }),
    })
    if (!response.ok) return false
    const next = sessionFromResponse(await response.json())
    if (!next) return false
    setSession(next)
    return true
  } catch {
    // offline or server down — keep the session and let the request fail so
    // the user sees a network error rather than being signed out
    return false
  }
}

/** Best-effort revocation; the local session is cleared either way. */
export async function signOut(): Promise<void> {
  const refreshToken = getSession()?.refreshToken
  clearSession()
  if (!refreshToken) return
  try {
    await fetch('/api/v1/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refreshToken }),
    })
  } catch {
    // the token expires on its own
  }
}

/**
 * Registers how to leave for the login page. The API client lives outside
 * React, so the router hands it a navigate function — a full page load would
 * drop the current route and any open form.
 */
export function setSessionExpiredHandler(handler: ((from: string) => void) | null): void {
  onExpired = handler
}

export function handleSessionExpired(): void {
  clearSession()
  const from = window.location.pathname + window.location.search
  if (onExpired) {
    onExpired(from)
  } else if (window.location.pathname !== '/login') {
    window.location.assign(`/login?from=${encodeURIComponent(from)}`)
  }
}

/**
 * Subscribe to auth state changes. Returns an unsubscribe function.
 * Compatible with React's useSyncExternalStore.
 */
export function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
