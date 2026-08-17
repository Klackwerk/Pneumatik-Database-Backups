import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import Table from 'react-bootstrap/Table'
import { Pencil, People } from 'react-bootstrap-icons'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
import { Skeleton } from '@/components/shared/Skeleton'
import { toast } from '@/lib/toast'

type User = components['schemas']['User']

interface UserForm {
  username: string
  email: string
  password: string
  enabled: boolean
  admin: boolean
}

const emptyForm: UserForm = { username: '', email: '', password: '', enabled: true, admin: true }

export function UsersPage() {
  const queryClient = useQueryClient()
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<User | null>(null)
  const [form, setForm] = useState<UserForm>(emptyForm)
  const [errors, setErrors] = useState<FieldErrors>({})

  const users = useQuery({
    queryKey: ['users'],
    queryFn: async () => (await client.GET('/api/v1/users', { params: { query: { pageSize: 200 } } })).data,
  })
  const me = useQuery({
    queryKey: ['me'],
    queryFn: async () => (await client.GET('/api/v1/users/me')).data,
  })

  const saveMutation = useMutation({
    mutationFn: async () => {
      const roles = form.admin ? ['ROLE_ADMIN'] : []
      const result = editing
        ? await client.PUT('/api/v1/users/{id}', {
            params: { path: { id: editing.id! } },
            body: {
              username: form.username.trim(),
              email: form.email.trim(),
              password: form.password || null,
              enabled: form.enabled,
              authorities: roles,
            },
          })
        : await client.POST('/api/v1/users', {
            body: {
              username: form.username.trim(),
              email: form.email.trim(),
              password: form.password,
              roles,
            },
          })
      if (result.error || !result.response.ok) throw parseFailure(result.error)
      return result.data
    },
    onSuccess: () => {
      toast.success(editing ? 'User saved' : 'User created')
      setDialogOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (failure: unknown) => {
      const { message, fields } = failure as ReturnType<typeof parseFailure>
      setErrors(fields ?? {})
      if (!fields || !Object.keys(fields).length) toast.error(message)
    },
  })

  function openAdd() {
    setEditing(null)
    setForm(emptyForm)
    setErrors({})
    setDialogOpen(true)
  }

  function openEdit(user: User) {
    setEditing(user)
    setForm({
      username: user.username ?? '',
      email: user.email ?? '',
      password: '',
      enabled: user.enabled ?? true,
      admin: user.roles?.includes('ROLE_ADMIN') ?? false,
    })
    setErrors({})
    setDialogOpen(true)
  }

  function set<K extends keyof UserForm>(key: K, value: UserForm[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    saveMutation.mutate()
  }

  const rows = users.data?.data ?? []
  const isSelf = (user: User) => me.data?.data?.id === user.id

  return (
    <>
      <PageHeader
        title="Users"
        description="Who can sign in to Pneumatik. Disable an account instead of deleting it."
        action={<Button onClick={openAdd}>Create user</Button>}
      />

      {users.isPending ? (
        <Skeleton height="16rem" />
      ) : users.isError ? (
        <ErrorState message="Users could not be loaded." onRetry={() => users.refetch()} />
      ) : !rows.length ? (
        <EmptyState icon={People} title="No users" description="Create the first account to sign in with." />
      ) : (
        <Card>
          <Table hover responsive className="mb-0 align-middle">
            <thead>
              <tr>
                <th scope="col">Username</th>
                <th scope="col">Email</th>
                <th scope="col">Roles</th>
                <th scope="col">Status</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((user) => (
                <tr key={user.id}>
                  <td className="fw-medium">
                    {user.username}
                    {isSelf(user) ? <span className="ms-2 small text-body-secondary">(you)</span> : null}
                  </td>
                  <td>{user.email}</td>
                  <td>
                    <div className="d-flex gap-1">
                      {user.roles?.length ? (
                        user.roles.map((role) => (
                          <Badge key={role} bg="secondary">
                            {role.replace('ROLE_', '').toLowerCase()}
                          </Badge>
                        ))
                      ) : (
                        <span className="text-body-secondary">none</span>
                      )}
                    </div>
                  </td>
                  <td>
                    {user.enabled ? (
                      <span className="badge bg-success-subtle text-success-emphasis border border-success-subtle">
                        Active
                      </span>
                    ) : (
                      <Badge bg="danger">Disabled</Badge>
                    )}
                  </td>
                  <td className="text-end">
                    <Button
                      variant="outline-secondary"
                      size="sm"
                      aria-label={`Edit ${user.username}`}
                      onClick={() => openEdit(user)}
                    >
                      <Pencil aria-hidden />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      <Modal show={dialogOpen} onHide={() => setDialogOpen(false)} centered aria-labelledby="user-dialog-title">
        <Modal.Header closeButton>
          <div>
            <Modal.Title id="user-dialog-title" className="fs-5">
              {editing ? `Edit ${editing.username}` : 'Create user'}
            </Modal.Title>
            <p className="mb-0 small text-body-secondary">
              {editing ? 'Account details and access.' : 'A new account that can sign in to Pneumatik.'}
            </p>
          </div>
        </Modal.Header>
        <Form onSubmit={onSubmit} noValidate>
          <Modal.Body>
            <div className="vstack gap-3">
              <FormField id="user-username" label="Username" error={errors.username}>
                <Form.Control
                  id="user-username"
                  required
                  autoComplete="off"
                  value={form.username}
                  isInvalid={!!errors.username}
                  onChange={(e) => set('username', e.target.value)}
                />
              </FormField>
              <FormField id="user-email" label="Email" error={errors.email}>
                <Form.Control
                  id="user-email"
                  type="email"
                  required
                  autoComplete="off"
                  value={form.email}
                  isInvalid={!!errors.email}
                  onChange={(e) => set('email', e.target.value)}
                />
              </FormField>
              <FormField
                id="user-password"
                label="Password"
                error={errors.password}
                description={editing ? 'Leave empty to keep the current password.' : 'At least 6 characters.'}
              >
                <Form.Control
                  id="user-password"
                  type="password"
                  autoComplete="new-password"
                  required={!editing}
                  minLength={6}
                  value={form.password}
                  isInvalid={!!errors.password}
                  onChange={(e) => set('password', e.target.value)}
                />
              </FormField>
              <Form.Check
                type="checkbox"
                id="user-admin"
                label="Administrator"
                checked={form.admin}
                onChange={(e) => set('admin', e.target.checked)}
              />
              {editing ? (
                <Form.Check
                  type="checkbox"
                  id="user-enabled"
                  checked={form.enabled}
                  disabled={isSelf(editing)}
                  onChange={(e) => set('enabled', e.target.checked)}
                  label={
                    <>
                      Account is active
                      {isSelf(editing) ? (
                        <span className="ms-1 small text-body-secondary">(you cannot disable yourself)</span>
                      ) : null}
                    </>
                  }
                />
              ) : null}
            </div>
          </Modal.Body>
          <Modal.Footer>
            <Button type="button" variant="secondary" onClick={() => setDialogOpen(false)} disabled={saveMutation.isPending}>
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={
                saveMutation.isPending || !form.username.trim() || !form.email.trim() || (!editing && form.password.length < 6)
              }
            >
              {saveMutation.isPending ? 'Saving…' : editing ? 'Save user' : 'Create user'}
            </Button>
          </Modal.Footer>
        </Form>
      </Modal>
    </>
  )
}
