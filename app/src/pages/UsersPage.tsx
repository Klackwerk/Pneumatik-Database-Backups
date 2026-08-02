import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Users as UsersIcon } from 'lucide-react'
import { toast } from 'sonner'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

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
        <Skeleton className="h-64 w-full" />
      ) : users.isError ? (
        <ErrorState message="Users could not be loaded." onRetry={() => users.refetch()} />
      ) : !rows.length ? (
        <EmptyState icon={UsersIcon} title="No users" description="Create the first account to sign in with." />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Username</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Roles</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((user) => (
                <TableRow key={user.id}>
                  <TableCell className="font-medium">
                    {user.username}
                    {isSelf(user) ? <span className="ml-2 text-xs text-muted-foreground">(you)</span> : null}
                  </TableCell>
                  <TableCell>{user.email}</TableCell>
                  <TableCell>
                    <div className="flex gap-1.5">
                      {user.roles?.length ? (
                        user.roles.map((role) => (
                          <Badge key={role} variant="secondary">
                            {role.replace('ROLE_', '').toLowerCase()}
                          </Badge>
                        ))
                      ) : (
                        <span className="text-muted-foreground">none</span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    {user.enabled ? (
                      <Badge variant="outline">Active</Badge>
                    ) : (
                      <Badge variant="destructive">Disabled</Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="icon" aria-label={`Edit ${user.username}`} onClick={() => openEdit(user)}>
                      <Pencil className="size-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{editing ? `Edit ${editing.username}` : 'Create user'}</DialogTitle>
            <DialogDescription>
              {editing ? 'Account details and access.' : 'A new account that can sign in to Pneumatik.'}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={onSubmit} noValidate>
            <FieldGroup>
              <FormField id="user-username" label="Username" error={errors.username}>
                <Input
                  id="user-username"
                  required
                  autoComplete="off"
                  value={form.username}
                  aria-invalid={!!errors.username}
                  onChange={(e) => set('username', e.target.value)}
                />
              </FormField>
              <FormField id="user-email" label="Email" error={errors.email}>
                <Input
                  id="user-email"
                  type="email"
                  required
                  autoComplete="off"
                  value={form.email}
                  aria-invalid={!!errors.email}
                  onChange={(e) => set('email', e.target.value)}
                />
              </FormField>
              <FormField
                id="user-password"
                label="Password"
                error={errors.password}
                description={editing ? 'Leave empty to keep the current password.' : 'At least 6 characters.'}
              >
                <Input
                  id="user-password"
                  type="password"
                  autoComplete="new-password"
                  required={!editing}
                  minLength={6}
                  value={form.password}
                  aria-invalid={!!errors.password}
                  onChange={(e) => set('password', e.target.value)}
                />
              </FormField>
              <FieldLabel className="gap-2">
                <Checkbox checked={form.admin} onCheckedChange={(checked) => set('admin', checked === true)} />
                Administrator
              </FieldLabel>
              {editing ? (
                <FieldLabel className="gap-2">
                  <Checkbox
                    checked={form.enabled}
                    disabled={isSelf(editing)}
                    onCheckedChange={(checked) => set('enabled', checked === true)}
                  />
                  Account is active
                  {isSelf(editing) ? (
                    <span className="text-xs text-muted-foreground">(you cannot disable yourself)</span>
                  ) : null}
                </FieldLabel>
              ) : null}
            </FieldGroup>
            <DialogFooter className="mt-6">
              <Button type="button" variant="outline" onClick={() => setDialogOpen(false)} disabled={saveMutation.isPending}>
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
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
