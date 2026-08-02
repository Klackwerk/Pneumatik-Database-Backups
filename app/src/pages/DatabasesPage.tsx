import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, Database as DatabaseIcon, History, Pencil, Trash2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { databaseTypeLabels, storageProviderLabels, triggerLabels } from '@/lib/format'

type Database = components['schemas']['Database']

interface DatabaseForm {
  friendlyName: string
  databaseName: string
  hostId: string
  user: string
  password: string
  storageProvider: string
  trigger: string
  databaseType: string
}

const emptyForm: DatabaseForm = {
  friendlyName: '',
  databaseName: '',
  hostId: '',
  user: '',
  password: '',
  storageProvider: 'DIRECT',
  trigger: 'TRIGGER_DAILY',
  databaseType: 'MYSQL',
}

export function DatabasesPage() {
  const queryClient = useQueryClient()
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Database | null>(null)
  const [form, setForm] = useState<DatabaseForm>(emptyForm)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [retentionFor, setRetentionFor] = useState<Database | null>(null)
  const [toDelete, setToDelete] = useState<Database | null>(null)

  const databases = useQuery({
    queryKey: ['databases'],
    queryFn: async () => (await client.GET('/api/v1/databases', { params: { query: { pageSize: 200 } } })).data,
  })
  const hosts = useQuery({
    queryKey: ['hosts'],
    queryFn: async () => (await client.GET('/api/v1/hosts', { params: { query: { pageSize: 200 } } })).data,
  })

  const saveMutation = useMutation({
    mutationFn: async () => {
      const body = {
        friendlyName: form.friendlyName.trim() || null,
        databaseName: form.databaseName.trim(),
        hostId: form.hostId,
        user: form.user.trim() || null,
        password: form.password || null,
        storageProvider: form.storageProvider as 'DIRECT' | 'S3',
        trigger: form.trigger as NonNullable<Database['trigger']>,
        databaseType: (form.databaseType || null) as Database['databaseType'],
      }
      const result = editing
        ? await client.PUT('/api/v1/databases/{id}', { params: { path: { id: editing.id! } }, body })
        : await client.POST('/api/v1/databases', { body })
      if (result.error || !result.response.ok) {
        throw parseFailure(result.error)
      }
      return result.data
    },
    onSuccess: () => {
      toast.success(editing ? 'Database saved' : 'Database added')
      setDialogOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['databases'] })
    },
    onError: (failure: unknown) => {
      const { message, fields } = failure as ReturnType<typeof parseFailure>
      setErrors(fields ?? {})
      if (!fields || !Object.keys(fields).length) {
        toast.error(message ?? 'The database could not be saved. Try again.')
      }
    },
  })

  const backupNow = useMutation({
    mutationFn: async (database: Database) => {
      const { response } = await client.POST('/api/v1/databases/{id}/backups', {
        params: { path: { id: database.id! } },
      })
      if (!response.ok) throw new Error(String(response.status))
    },
    onSuccess: (_, database) => {
      toast.success(`Backup of ${database.name} queued`, {
        description: 'It runs within a minute. Watch progress on the Backups page.',
      })
      void queryClient.invalidateQueries({ queryKey: ['backups'] })
    },
    onError: () => toast.error('The backup could not be queued. Try again.'),
  })

  const deleteMutation = useMutation({
    mutationFn: async (database: Database) => {
      const { response } = await client.DELETE('/api/v1/databases/{id}', {
        params: { path: { id: database.id! } },
      })
      if (!response.ok) throw new Error(String(response.status))
    },
    onSuccess: (_, database) => {
      toast.success(`${database.name} deleted`)
      setToDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['databases'] })
      void queryClient.invalidateQueries({ queryKey: ['backups'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: () => toast.error('The database could not be deleted. Try again.'),
  })

  function openAdd() {
    setEditing(null)
    setForm(emptyForm)
    setErrors({})
    setDialogOpen(true)
  }

  function openEdit(database: Database) {
    setEditing(database)
    setForm({
      friendlyName: database.friendlyName ?? '',
      databaseName: database.databaseName ?? '',
      hostId: database.hostId != null ? String(database.hostId) : '',
      user: database.user ?? '',
      password: '',
      storageProvider: database.storageProvider ?? 'DIRECT',
      trigger: database.trigger ?? 'TRIGGER_DAILY',
      databaseType: database.databaseType ?? 'MYSQL',
    })
    setErrors({})
    setDialogOpen(true)
  }

  function set<K extends keyof DatabaseForm>(key: K, value: DatabaseForm[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    saveMutation.mutate()
  }

  const rows = databases.data?.data ?? []

  return (
    <>
      <PageHeader
        title="Databases"
        description="What gets backed up, how often, and where the archives go."
        action={<Button onClick={openAdd}>Add database</Button>}
      />

      {databases.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : databases.isError ? (
        <ErrorState message="Databases could not be loaded." onRetry={() => databases.refetch()} />
      ) : !rows.length ? (
        <EmptyState
          icon={DatabaseIcon}
          title="No databases yet"
          description={
            hosts.data?.data?.length
              ? 'Add a database to start backing it up on a schedule.'
              : 'First add a host, then the databases running on it.'
          }
          action={
            hosts.data?.data?.length ? (
              <Button onClick={openAdd}>Add database</Button>
            ) : (
              <Button asChild>
                <Link to="/hosts">Go to hosts</Link>
              </Button>
            )
          }
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Host</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Schedule</TableHead>
                <TableHead>Storage</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((database) => (
                <TableRow key={database.id}>
                  <TableCell className="font-medium">{database.name}</TableCell>
                  <TableCell>{database.hostName}</TableCell>
                  <TableCell>{database.databaseType ? databaseTypeLabels[database.databaseType] : 'MySQL / MariaDB'}</TableCell>
                  <TableCell>{database.trigger ? (triggerLabels[database.trigger] ?? database.trigger) : '—'}</TableCell>
                  <TableCell>{database.storageProvider ? storageProviderLabels[database.storageProvider] : '—'}</TableCell>
                  <TableCell className="text-right">
                    <div className="inline-flex gap-1">
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <Button
                            variant="ghost"
                            size="icon"
                            aria-label={`Back up ${database.name} now`}
                            disabled={backupNow.isPending}
                            onClick={() => backupNow.mutate(database)}
                          >
                            <Archive className="size-4" />
                          </Button>
                        </TooltipTrigger>
                        <TooltipContent>Back up now</TooltipContent>
                      </Tooltip>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <Button
                            variant="ghost"
                            size="icon"
                            aria-label={`Retention policy of ${database.name}`}
                            onClick={() => setRetentionFor(database)}
                          >
                            <History className="size-4" />
                          </Button>
                        </TooltipTrigger>
                        <TooltipContent>Retention policy</TooltipContent>
                      </Tooltip>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <Button
                            variant="ghost"
                            size="icon"
                            aria-label={`Edit ${database.name}`}
                            onClick={() => openEdit(database)}
                          >
                            <Pencil className="size-4" />
                          </Button>
                        </TooltipTrigger>
                        <TooltipContent>Edit</TooltipContent>
                      </Tooltip>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <Button
                            variant="ghost"
                            size="icon"
                            aria-label={`Delete ${database.name}`}
                            onClick={() => setToDelete(database)}
                          >
                            <Trash2 className="size-4" />
                          </Button>
                        </TooltipTrigger>
                        <TooltipContent>Delete</TooltipContent>
                      </Tooltip>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? `Edit ${editing.name}` : 'Add database'}</DialogTitle>
            <DialogDescription>
              {editing ? 'Backup settings for this database.' : 'A database to back up on a schedule.'}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={onSubmit} noValidate>
            <FieldGroup>
              <div className="grid grid-cols-2 gap-4">
                <FormField id="db-name" label="Database name" error={errors.databaseName}>
                  <Input
                    id="db-name"
                    required
                    placeholder="shop"
                    value={form.databaseName}
                    aria-invalid={!!errors.databaseName}
                    onChange={(e) => set('databaseName', e.target.value)}
                  />
                </FormField>
                <FormField
                  id="db-friendlyName"
                  label="Display name"
                  error={errors.friendlyName}
                  description="Optional; shown in lists."
                >
                  <Input
                    id="db-friendlyName"
                    value={form.friendlyName}
                    onChange={(e) => set('friendlyName', e.target.value)}
                  />
                </FormField>
              </div>

              <FormField id="db-host" label="Host" error={errors.hostId}>
                <Select value={form.hostId} onValueChange={(value) => set('hostId', value)}>
                  <SelectTrigger id="db-host" aria-invalid={!!errors.hostId}>
                    <SelectValue placeholder="Choose a host" />
                  </SelectTrigger>
                  <SelectContent>
                    {(hosts.data?.data ?? []).map((host) => (
                      <SelectItem key={host.id} value={String(host.id)}>
                        {host.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormField>

              <FormField id="db-type" label="Database type" error={errors.databaseType}>
                <Select value={form.databaseType} onValueChange={(value) => set('databaseType', value)}>
                  <SelectTrigger id="db-type">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {Object.entries(databaseTypeLabels).map(([value, label]) => (
                      <SelectItem key={value} value={value}>
                        {label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormField>

              <div className="grid grid-cols-2 gap-4">
                <FormField id="db-user" label="Username" error={errors.user}>
                  <Input id="db-user" value={form.user} onChange={(e) => set('user', e.target.value)} />
                </FormField>
                <FormField
                  id="db-password"
                  label="Password"
                  error={errors.password}
                  description={
                    editing?.hasPassword ? 'A password is stored. Leave empty to keep it.' : 'Stored encrypted.'
                  }
                >
                  <Input
                    id="db-password"
                    type="password"
                    autoComplete="new-password"
                    value={form.password}
                    onChange={(e) => set('password', e.target.value)}
                  />
                </FormField>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <FormField id="db-trigger" label="Schedule" error={errors.trigger}>
                  <Select value={form.trigger} onValueChange={(value) => set('trigger', value)}>
                    <SelectTrigger id="db-trigger">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Object.entries(triggerLabels).map(([value, label]) => (
                        <SelectItem key={value} value={value}>
                          {label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </FormField>
                <FormField id="db-storage" label="Storage" error={errors.storageProvider}>
                  <Select value={form.storageProvider} onValueChange={(value) => set('storageProvider', value)}>
                    <SelectTrigger id="db-storage">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Object.entries(storageProviderLabels).map(([value, label]) => (
                        <SelectItem key={value} value={value}>
                          {label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </FormField>
              </div>
            </FieldGroup>
            <DialogFooter className="mt-6">
              <Button type="button" variant="outline" onClick={() => setDialogOpen(false)} disabled={saveMutation.isPending}>
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={saveMutation.isPending || !form.databaseName.trim() || !form.hostId}
              >
                {saveMutation.isPending ? 'Saving…' : editing ? 'Save database' : 'Add database'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {retentionFor ? (
        <RetentionDialog database={retentionFor} onClose={() => setRetentionFor(null)} />
      ) : null}

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title={toDelete ? `Delete ${toDelete.name}?` : ''}
        description={toDelete ? <DatabaseDeleteWarning database={toDelete} /> : ''}
        confirmLabel="Delete database"
        pending={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete)}
      />
    </>
  )
}

/**
 * Says what a delete actually destroys. Removing the schedule is routine;
 * destroying years of restorable archives is not, so the backup count leads
 * when there is one.
 */
function DatabaseDeleteWarning({ database }: { database: Database }) {
  const backupCount = database.backupCount ?? 0

  if (!backupCount) {
    return <>This database has no backups. Its configuration and retention policy are removed.</>
  }

  return (
    <>
      <strong className="text-destructive">
        {backupCount} backup{backupCount === 1 ? '' : 's'} and their stored archives
      </strong>{' '}
      are permanently deleted along with this database, its schedule and its retention policy. Stored
      archives cannot be recovered afterwards — download anything you still need first.
    </>
  )
}

function RetentionDialog({ database, onClose }: { database: Database; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [enabled, setEnabled] = useState(true)
  const [keepCount, setKeepCount] = useState('')
  const [keepDays, setKeepDays] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})

  const policy = useQuery({
    queryKey: ['retention', database.id],
    queryFn: async () => {
      const { data, response } = await client.GET('/api/v1/databases/{databaseId}/retention-policy', {
        params: { path: { databaseId: database.id! } },
      })
      if (response.status === 404) return null
      return data ?? null
    },
    retry: false,
  })

  // hydrate the form once the (possibly missing) policy has loaded
  const [hydrated, setHydrated] = useState(false)
  if (!hydrated && !policy.isPending) {
    setHydrated(true)
    const existing = policy.data?.data
    if (existing) {
      setEnabled(existing.enabled ?? true)
      setKeepCount(existing.keepCount != null ? String(existing.keepCount) : '')
      setKeepDays(existing.keepDays != null ? String(existing.keepDays) : '')
    }
  }

  const saveMutation = useMutation({
    mutationFn: async () => {
      const result = await client.PUT('/api/v1/databases/{databaseId}/retention-policy', {
        params: { path: { databaseId: database.id! } },
        body: {
          keepCount: keepCount ? Number(keepCount) : null,
          keepDays: keepDays ? Number(keepDays) : null,
          enabled,
        },
      })
      if (result.error || !result.response.ok) throw parseFailure(result.error)
    },
    onSuccess: () => {
      toast.success(`Retention policy for ${database.name} saved`)
      void queryClient.invalidateQueries({ queryKey: ['retention', database.id] })
      onClose()
    },
    onError: (failure: unknown) => {
      const { message, fields } = failure as ReturnType<typeof parseFailure>
      setErrors(fields ?? {})
      if (!fields || !Object.keys(fields).length) toast.error(message)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: async () => {
      const { response } = await client.DELETE('/api/v1/databases/{databaseId}/retention-policy', {
        params: { path: { databaseId: database.id! } },
      })
      if (!response.ok) throw new Error(String(response.status))
    },
    onSuccess: () => {
      toast.success(`Retention policy for ${database.name} removed — backups are kept forever again`)
      void queryClient.invalidateQueries({ queryKey: ['retention', database.id] })
      onClose()
    },
    onError: () => toast.error('The policy could not be removed. Try again.'),
  })

  const hasPolicy = !!policy.data?.data
  const pending = saveMutation.isPending || deleteMutation.isPending

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Retention for {database.name}</DialogTitle>
          <DialogDescription>
            Old successful backups are deleted automatically every night. Failed runs are always kept as history.
          </DialogDescription>
        </DialogHeader>
        {policy.isPending ? (
          <Skeleton className="h-32 w-full" />
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              saveMutation.mutate()
            }}
            noValidate
          >
            <FieldGroup>
              <FormField
                id="retention-keepCount"
                label="Keep at most"
                error={errors.keepCount}
                description="Number of most recent backups to keep. Empty means no count limit."
              >
                <Input
                  id="retention-keepCount"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  placeholder="e.g. 30"
                  value={keepCount}
                  aria-invalid={!!errors.keepCount}
                  onChange={(e) => setKeepCount(e.target.value)}
                />
              </FormField>
              <FormField
                id="retention-keepDays"
                label="Delete after"
                error={errors.keepDays}
                description="Age in days after which backups are deleted. Empty means no age limit."
              >
                <Input
                  id="retention-keepDays"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  placeholder="e.g. 90"
                  value={keepDays}
                  aria-invalid={!!errors.keepDays}
                  onChange={(e) => setKeepDays(e.target.value)}
                />
              </FormField>
              <FieldLabel className="gap-2">
                <Checkbox checked={enabled} onCheckedChange={(checked) => setEnabled(checked === true)} />
                Policy is active
              </FieldLabel>
            </FieldGroup>
            <DialogFooter className="mt-6">
              {hasPolicy ? (
                <Button
                  type="button"
                  variant="ghost"
                  className="mr-auto text-destructive hover:text-destructive"
                  disabled={pending}
                  onClick={() => deleteMutation.mutate()}
                >
                  Remove policy
                </Button>
              ) : null}
              <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
                Cancel
              </Button>
              <Button type="submit" disabled={pending || (!keepCount && !keepDays)}>
                {saveMutation.isPending ? 'Saving…' : 'Save policy'}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
