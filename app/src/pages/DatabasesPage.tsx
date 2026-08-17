import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import OverlayTrigger from 'react-bootstrap/OverlayTrigger'
import Table from 'react-bootstrap/Table'
import Tooltip from 'react-bootstrap/Tooltip'
import { Archive, ClockHistory, Database as DatabaseIcon, Pencil, Trash } from 'react-bootstrap-icons'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
import { Skeleton } from '@/components/shared/Skeleton'
import { databaseTypeLabels, storageProviderLabels, triggerLabels } from '@/lib/format'
import { toast } from '@/lib/toast'

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
        <Skeleton height="16rem" />
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
              <Link to="/hosts" className="btn btn-primary">
                Go to hosts
              </Link>
            )
          }
        />
      ) : (
        <Card>
          <Table hover responsive className="mb-0 align-middle">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Host</th>
                <th scope="col">Type</th>
                <th scope="col">Schedule</th>
                <th scope="col">Storage</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((database) => (
                <tr key={database.id}>
                  <td className="fw-medium">{database.name}</td>
                  <td>{database.hostName}</td>
                  <td>{database.databaseType ? databaseTypeLabels[database.databaseType] : 'MySQL / MariaDB'}</td>
                  <td>{database.trigger ? (triggerLabels[database.trigger] ?? database.trigger) : '—'}</td>
                  <td>{database.storageProvider ? storageProviderLabels[database.storageProvider] : '—'}</td>
                  <td className="text-end">
                    <div className="btn-group btn-group-sm" role="group" aria-label={`Actions for ${database.name}`}>
                      <OverlayTrigger overlay={<Tooltip id={`tt-backup-${database.id}`}>Back up now</Tooltip>}>
                        <Button
                          variant="outline-secondary"
                          aria-label={`Back up ${database.name} now`}
                          disabled={backupNow.isPending}
                          onClick={() => backupNow.mutate(database)}
                        >
                          <Archive aria-hidden />
                        </Button>
                      </OverlayTrigger>
                      <OverlayTrigger overlay={<Tooltip id={`tt-retention-${database.id}`}>Retention policy</Tooltip>}>
                        <Button
                          variant="outline-secondary"
                          aria-label={`Retention policy of ${database.name}`}
                          onClick={() => setRetentionFor(database)}
                        >
                          <ClockHistory aria-hidden />
                        </Button>
                      </OverlayTrigger>
                      <OverlayTrigger overlay={<Tooltip id={`tt-edit-${database.id}`}>Edit</Tooltip>}>
                        <Button
                          variant="outline-secondary"
                          aria-label={`Edit ${database.name}`}
                          onClick={() => openEdit(database)}
                        >
                          <Pencil aria-hidden />
                        </Button>
                      </OverlayTrigger>
                      <OverlayTrigger overlay={<Tooltip id={`tt-delete-${database.id}`}>Delete</Tooltip>}>
                        <Button
                          variant="outline-danger"
                          aria-label={`Delete ${database.name}`}
                          onClick={() => setToDelete(database)}
                        >
                          <Trash aria-hidden />
                        </Button>
                      </OverlayTrigger>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      <Modal show={dialogOpen} onHide={() => setDialogOpen(false)} centered scrollable aria-labelledby="database-dialog-title">
        <Modal.Header closeButton>
          <div>
            <Modal.Title id="database-dialog-title" className="fs-5">
              {editing ? `Edit ${editing.name}` : 'Add database'}
            </Modal.Title>
            <p className="mb-0 small text-body-secondary">
              {editing ? 'Backup settings for this database.' : 'A database to back up on a schedule.'}
            </p>
          </div>
        </Modal.Header>
        <Form onSubmit={onSubmit} noValidate>
          <Modal.Body>
            <div className="vstack gap-3">
              <div className="row g-3">
                <div className="col-6">
                  <FormField id="db-name" label="Database name" error={errors.databaseName}>
                    <Form.Control
                      id="db-name"
                      required
                      placeholder="shop"
                      value={form.databaseName}
                      isInvalid={!!errors.databaseName}
                      onChange={(e) => set('databaseName', e.target.value)}
                    />
                  </FormField>
                </div>
                <div className="col-6">
                  <FormField
                    id="db-friendlyName"
                    label="Display name"
                    error={errors.friendlyName}
                    description="Optional; shown in lists."
                  >
                    <Form.Control
                      id="db-friendlyName"
                      value={form.friendlyName}
                      isInvalid={!!errors.friendlyName}
                      onChange={(e) => set('friendlyName', e.target.value)}
                    />
                  </FormField>
                </div>
              </div>

              <FormField id="db-host" label="Host" error={errors.hostId}>
                <Form.Select
                  id="db-host"
                  value={form.hostId}
                  isInvalid={!!errors.hostId}
                  onChange={(e) => set('hostId', e.target.value)}
                >
                  <option value="" disabled>
                    Choose a host
                  </option>
                  {(hosts.data?.data ?? []).map((host) => (
                    <option key={host.id} value={String(host.id)}>
                      {host.name}
                    </option>
                  ))}
                </Form.Select>
              </FormField>

              <FormField id="db-type" label="Database type" error={errors.databaseType}>
                <Form.Select
                  id="db-type"
                  value={form.databaseType}
                  onChange={(e) => set('databaseType', e.target.value)}
                >
                  {Object.entries(databaseTypeLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </Form.Select>
              </FormField>

              <div className="row g-3">
                <div className="col-6">
                  <FormField id="db-user" label="Username" error={errors.user}>
                    <Form.Control id="db-user" value={form.user} onChange={(e) => set('user', e.target.value)} />
                  </FormField>
                </div>
                <div className="col-6">
                  <FormField
                    id="db-password"
                    label="Password"
                    error={errors.password}
                    description={
                      editing?.hasPassword ? 'A password is stored. Leave empty to keep it.' : 'Stored encrypted.'
                    }
                  >
                    <Form.Control
                      id="db-password"
                      type="password"
                      autoComplete="new-password"
                      value={form.password}
                      isInvalid={!!errors.password}
                      onChange={(e) => set('password', e.target.value)}
                    />
                  </FormField>
                </div>
              </div>

              <div className="row g-3">
                <div className="col-6">
                  <FormField id="db-trigger" label="Schedule" error={errors.trigger}>
                    <Form.Select id="db-trigger" value={form.trigger} onChange={(e) => set('trigger', e.target.value)}>
                      {Object.entries(triggerLabels).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </Form.Select>
                  </FormField>
                </div>
                <div className="col-6">
                  <FormField id="db-storage" label="Storage" error={errors.storageProvider}>
                    <Form.Select
                      id="db-storage"
                      value={form.storageProvider}
                      onChange={(e) => set('storageProvider', e.target.value)}
                    >
                      {Object.entries(storageProviderLabels).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </Form.Select>
                  </FormField>
                </div>
              </div>
            </div>
          </Modal.Body>
          <Modal.Footer>
            <Button type="button" variant="secondary" onClick={() => setDialogOpen(false)} disabled={saveMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={saveMutation.isPending || !form.databaseName.trim() || !form.hostId}>
              {saveMutation.isPending ? 'Saving…' : editing ? 'Save database' : 'Add database'}
            </Button>
          </Modal.Footer>
        </Form>
      </Modal>

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
      <strong className="text-danger">
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
    <Modal show onHide={onClose} centered aria-labelledby="retention-dialog-title">
      <Modal.Header closeButton>
        <div>
          <Modal.Title id="retention-dialog-title" className="fs-5">
            Retention for {database.name}
          </Modal.Title>
          <p className="mb-0 small text-body-secondary">
            Old successful backups are deleted automatically every night. Failed runs are always kept as history.
          </p>
        </div>
      </Modal.Header>
      {policy.isPending ? (
        <Modal.Body>
          <Skeleton height="8rem" />
        </Modal.Body>
      ) : (
        <Form
          onSubmit={(e) => {
            e.preventDefault()
            saveMutation.mutate()
          }}
          noValidate
        >
          <Modal.Body>
            <div className="vstack gap-3">
              <FormField
                id="retention-keepCount"
                label="Keep at most"
                error={errors.keepCount}
                description="Number of most recent backups to keep. Empty means no count limit."
              >
                <Form.Control
                  id="retention-keepCount"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  placeholder="e.g. 30"
                  value={keepCount}
                  isInvalid={!!errors.keepCount}
                  onChange={(e) => setKeepCount(e.target.value)}
                />
              </FormField>
              <FormField
                id="retention-keepDays"
                label="Delete after"
                error={errors.keepDays}
                description="Age in days after which backups are deleted. Empty means no age limit."
              >
                <Form.Control
                  id="retention-keepDays"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  placeholder="e.g. 90"
                  value={keepDays}
                  isInvalid={!!errors.keepDays}
                  onChange={(e) => setKeepDays(e.target.value)}
                />
              </FormField>
              <Form.Check
                type="checkbox"
                id="retention-enabled"
                label="Policy is active"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
              />
            </div>
          </Modal.Body>
          <Modal.Footer>
            {hasPolicy ? (
              <Button
                type="button"
                variant="outline-danger"
                className="me-auto"
                disabled={pending}
                onClick={() => deleteMutation.mutate()}
              >
                Remove policy
              </Button>
            ) : null}
            <Button type="button" variant="secondary" onClick={onClose} disabled={pending}>
              Cancel
            </Button>
            <Button type="submit" disabled={pending || (!keepCount && !keepDays)}>
              {saveMutation.isPending ? 'Saving…' : 'Save policy'}
            </Button>
          </Modal.Footer>
        </Form>
      )}
    </Modal>
  )
}
