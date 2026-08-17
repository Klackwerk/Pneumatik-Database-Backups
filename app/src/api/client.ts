import createClient, { type Middleware } from 'openapi-fetch'

import { ensureFreshSession, getToken, handleSessionExpired } from '@/lib/auth'

import type { paths } from './schema'

/** endpoints that establish a session — a 401 from these is the answer, not a stale token */
const AUTH_PATHS = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout']

function isAuthEndpoint(url: string): boolean {
  const path = new URL(url, window.location.origin).pathname
  return AUTH_PATHS.includes(path)
}

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    if (!isAuthEndpoint(request.url)) {
      // renew before sending rather than reacting to a 401, so a token that
      // expired while the tab was asleep never costs the user their work
      await ensureFreshSession()
    }
    const token = getToken()
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`)
    }
    return request
  },
  onResponse({ request, response }) {
    // renewal already had its chance: a 401 here means the session is gone
    if (response.status === 401 && !isAuthEndpoint(request.url)) {
      handleSessionExpired()
    }
    return response
  },
}

export const client = createClient<paths>({ baseUrl: '/' })

client.use(authMiddleware)
