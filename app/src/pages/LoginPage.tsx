import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'

import { client } from '@/api/client'
import { Logo } from '@/components/brand/Logo'
import { FormField } from '@/components/shared/FormField'
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
    <main className="d-flex min-vh-100 align-items-center justify-content-center bg-body-tertiary p-3">
      <Card className="w-100 shadow-sm" style={{ maxWidth: '24rem' }}>
        <Card.Body className="p-4">
          <div className="text-center mb-4">
            <Logo className="mb-3" width={64} height={64} title="Pneumatik" />
            <Card.Title as="h1" className="fs-5 mb-1">
              Pneumatik
            </Card.Title>
            <p className="text-body-secondary small mb-0">Sign in to manage your database backups.</p>
          </div>
          <Form onSubmit={onSubmit} noValidate>
            <div className="vstack gap-3">
              <FormField id="username" label="Username">
                <Form.Control
                  id="username"
                  autoComplete="username"
                  autoFocus
                  required
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                />
              </FormField>
              <FormField id="password" label="Password">
                <Form.Control
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </FormField>
              {error ? (
                <p role="alert" className="small text-danger mb-0">
                  {error}
                </p>
              ) : null}
              <Button type="submit" className="w-100" disabled={pending || !username || !password}>
                {pending ? 'Signing in…' : 'Sign in'}
              </Button>
            </div>
          </Form>
        </Card.Body>
      </Card>
    </main>
  )
}
