import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { client } from '@/api/client'
import { Logo } from '@/components/brand/Logo'
import { FormField } from '@/components/shared/FormField'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { FieldGroup } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { sessionFromResponse, setSession } from '@/lib/auth'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setPending(true)
    try {
      const { data, response } = await client.POST('/api/v1/auth/login', {
        body: { username, password },
      })
      const session = data ? sessionFromResponse(data) : null
      if (session) {
        setSession(session)
        // the route the user was on, whether the redirect came from the
        // router (state) or from a full page load (query string)
        const from =
          (location.state as { from?: string } | null)?.from ??
          new URLSearchParams(location.search).get('from')
        navigate(from && from !== '/login' ? from : '/', { replace: true })
      } else if (response.status === 429) {
        const retryAfter = response.headers.get('Retry-After')
        const minutes = retryAfter ? Math.ceil(Number(retryAfter) / 60) : null
        setError(
          minutes
            ? `Too many failed attempts. Try again in about ${minutes} minute${minutes === 1 ? '' : 's'}.`
            : 'Too many failed attempts. Try again later.',
        )
      } else if (response.status === 401) {
        setError('Wrong username or password.')
      } else {
        setError('Sign-in failed. Try again in a moment.')
      }
    } catch {
      setError('Could not reach the server. Check your connection and try again.')
    } finally {
      setPending(false)
    }
  }

  return (
    <main className="flex min-h-svh items-center justify-center bg-background p-4">
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          <Logo className="mx-auto mb-3 size-16 text-foreground" title="Pneumatik" />
          <CardTitle className="text-lg">Pneumatik</CardTitle>
          <CardDescription>Sign in to manage your database backups.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} noValidate>
            <FieldGroup>
              <FormField id="username" label="Username">
                <Input
                  id="username"
                  autoComplete="username"
                  autoFocus
                  required
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                />
              </FormField>
              <FormField id="password" label="Password">
                <Input
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </FormField>
              {error ? (
                <p role="alert" className="text-sm text-destructive">
                  {error}
                </p>
              ) : null}
              <Button type="submit" className="w-full" disabled={pending || !username || !password}>
                {pending ? 'Signing in…' : 'Sign in'}
              </Button>
            </FieldGroup>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}
