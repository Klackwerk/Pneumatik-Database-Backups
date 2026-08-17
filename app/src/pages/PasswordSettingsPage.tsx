import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'

import { client } from '@/api/client'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
import { toast } from '@/lib/toast'

export function PasswordSettingsPage() {
  const [passwordOld, setPasswordOld] = useState('')
  const [passwordNew, setPasswordNew] = useState('')
  const [passwordNewConfirm, setPasswordNewConfirm] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})

  const changeMutation = useMutation({
    mutationFn: async () => {
      const result = await client.PUT('/api/v1/users/me/password', {
        body: { passwordOld, passwordNew, passwordNewConfirm },
      })
      if (result.error || !result.response.ok) throw parseFailure(result.error)
    },
    onSuccess: () => {
      toast.success('Password changed')
      setPasswordOld('')
      setPasswordNew('')
      setPasswordNewConfirm('')
      setErrors({})
    },
    onError: (failure: unknown) => {
      const { message, fields } = failure as ReturnType<typeof parseFailure>
      setErrors(fields ?? {})
      if (!fields || !Object.keys(fields).length) toast.error(message)
    },
  })

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setErrors({})
    changeMutation.mutate()
  }

  const confirmMismatch = passwordNewConfirm.length > 0 && passwordNew !== passwordNewConfirm

  return (
    <>
      <PageHeader title="Change password" description="Pick a new password for your account." />
      <Card style={{ maxWidth: '28rem' }}>
        <Card.Body>
          <Form onSubmit={onSubmit} noValidate>
            <div className="vstack gap-3">
              <FormField id="password-old" label="Current password" error={errors.passwordOld}>
                <Form.Control
                  id="password-old"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={passwordOld}
                  isInvalid={!!errors.passwordOld}
                  onChange={(e) => setPasswordOld(e.target.value)}
                />
              </FormField>
              <FormField
                id="password-new"
                label="New password"
                error={errors.passwordNew}
                description="At least 6 characters."
              >
                <Form.Control
                  id="password-new"
                  type="password"
                  autoComplete="new-password"
                  required
                  minLength={6}
                  value={passwordNew}
                  isInvalid={!!errors.passwordNew}
                  onChange={(e) => setPasswordNew(e.target.value)}
                />
              </FormField>
              <FormField
                id="password-confirm"
                label="Repeat new password"
                error={errors.passwordNewConfirm ?? (confirmMismatch ? 'Passwords do not match' : undefined)}
              >
                <Form.Control
                  id="password-confirm"
                  type="password"
                  autoComplete="new-password"
                  required
                  value={passwordNewConfirm}
                  isInvalid={!!errors.passwordNewConfirm || confirmMismatch}
                  onChange={(e) => setPasswordNewConfirm(e.target.value)}
                />
              </FormField>
              <div>
                <Button
                  type="submit"
                  disabled={
                    changeMutation.isPending ||
                    !passwordOld ||
                    passwordNew.length < 6 ||
                    passwordNew !== passwordNewConfirm
                  }
                >
                  {changeMutation.isPending ? 'Changing…' : 'Change password'}
                </Button>
              </div>
            </div>
          </Form>
        </Card.Body>
      </Card>
    </>
  )
}
