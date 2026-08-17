import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import Table from 'react-bootstrap/Table'
import { HddRack, Pencil, Trash } from 'react-bootstrap-icons'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
import { Skeleton } from '@/components/shared/Skeleton'
import { toast } from '@/lib/toast'

type Host = components['schemas']['Host']

interface HostForm {
  friendlyName: string
  hostname: string
  port: string
  sshHostname: string
  sshUser: string
  sshPort: string
  sshKey: string
  useSSL: boolean
  verifyHostKey: boolean
  hostKey: string
}

const emptyForm: HostForm = {
  friendlyName: '',
  hostname: '',
  port: '',
  sshHostname: '',
  sshUser: '',
  sshPort: '',
  sshKey: '',
  useSSL: false,
  // on for new hosts: the safe default, and nothing is pinned yet so the
  // first connection just records the key
  verifyHostKey: true,
  hostKey: '',
}

function formFor(host: Host): HostForm {
  return {
    friendlyName: host.friendlyName ?? '',
    hostname: host.hostname ?? '',
    port: host.port != null ? String(host.port) : '',
    sshHostname: host.sshHostname ?? '',
    sshUser: host.sshUser ?? '',
    sshPort: host.sshPort != null ? String(host.sshPort) : '',
    sshKey: '',
    useSSL: host.useSSL ?? false,
    verifyHostKey: host.verifyHostKey ?? false,
    hostKey: host.hostKey ?? '',
  }
}

export function HostsPage() {
  const queryClient = useQueryClient()
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Host | null>(null)
  const [form, setForm] = useState<HostForm>(emptyForm)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [toDelete, setToDelete] = useState<Host | null>(null)

  const hosts = useQuery({
    queryKey: ['hosts'],
    queryFn: async () => (await client.GET('/api/v1/hosts', { params: { query: { pageSize: 200 } } })).data,
  })

  // the listing carries databaseCount; the detail view adds the backup count
  // and the database names, which is what the warning needs to be specific
  const deletionImpact = useQuery({
    queryKey: ['hosts', toDelete?.id, 'impact'],
    enabled: toDelete !== null,
    queryFn: async () =>
      (await client.GET('/api/v1/hosts/{id}', { params: { path: { id: toDelete!.id! } } })).data?.data,
  })

  const deleteMutation = useMutation({
    mutationFn: async (host: Host) => {
      const { response } = await client.DELETE('/api/v1/hosts/{id}', {
        params: { path: { id: host.id! } },
      })
      if (!response.ok) throw new Error(String(response.status))
    },
    onSuccess: (_, host) => {
      toast.success(`${host.name} deleted`)
      setToDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['hosts'] })
      void queryClient.invalidateQueries({ queryKey: ['databases'] })
      void queryClient.invalidateQueries({ queryKey: ['backups'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: () => toast.error('The host could not be deleted. Try again.'),
  })

  const saveMutation = useMutation({
    mutationFn: async () => {
      const body = {
        friendlyName: form.friendlyName.trim() || null,
        hostname: form.hostname.trim(),
        port: form.port ? Number(form.port) : null,
        sshHostname: form.sshHostname.trim() || null,
        sshUser: form.sshUser.trim() || null,
        sshPort: form.sshPort ? Number(form.sshPort) : null,
        sshKey: form.sshKey || null,
        useSSL: form.useSSL,
        verifyHostKey: form.verifyHostKey,
        hostKey: form.hostKey.trim(),
      }
      const result = editing
        ? await client.PUT('/api/v1/hosts/{id}', { params: { path: { id: editing.id! } }, body })
        : await client.POST('/api/v1/hosts', { body })
      if (result.error || !result.response.ok) {
        throw parseFailure(result.error)
      }
      return result.data
    },
    onSuccess: () => {
      toast.success(editing ? 'Host saved' : 'Host added')
      closeDialog()
      void queryClient.invalidateQueries({ queryKey: ['hosts'] })
    },
    onError: (failure: unknown) => {
      const { message, fields } = failure as ReturnType<typeof parseFailure>
      setErrors(fields ?? {})
      if (!fields || !Object.keys(fields).length) {
        toast.error(message ?? 'The host could not be saved. Try again.')
      }
    },
  })

  function openAdd() {
    setEditing(null)
    setForm(emptyForm)
    setErrors({})
    setDialogOpen(true)
  }

  function openEdit(host: Host) {
    setEditing(host)
    setForm(formFor(host))
    setErrors({})
    setDialogOpen(true)
  }

  function closeDialog() {
    setDialogOpen(false)
    setErrors({})
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    saveMutation.mutate()
  }

  function set<K extends keyof HostForm>(key: K, value: HostForm[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  const rows = hosts.data?.data ?? []

  return (
    <>
      <PageHeader
        title="Hosts"
        description="The servers your databases run on, with optional SSH access for remote dumps."
        action={<Button onClick={openAdd}>Add host</Button>}
      />

      {hosts.isPending ? (
        <Skeleton height="16rem" />
      ) : hosts.isError ? (
        <ErrorState message="Hosts could not be loaded." onRetry={() => hosts.refetch()} />
      ) : !rows.length ? (
        <EmptyState
          icon={HddRack}
          title="No hosts yet"
          description="Add the server a database runs on. Databases reference a host for connection details."
          action={<Button onClick={openAdd}>Add host</Button>}
        />
      ) : (
        <Card>
          <Table hover responsive className="mb-0 align-middle">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Address</th>
                <th scope="col">Connection</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((host) => (
                <tr key={host.id}>
                  <td className="fw-medium">{host.name}</td>
                  <td>
                    {host.hostname}:{host.port}
                  </td>
                  <td>
                    <div className="d-flex flex-wrap gap-1">
                      {host.useSSL ? <Badge bg="secondary">SSL</Badge> : null}
                      {host.hasSshKey ? <Badge bg="secondary">via SSH</Badge> : null}
                      {host.hasSshKey && !host.verifyHostKey ? (
                        <Badge bg="danger" title="The SSH host key is not verified">
                          host key unverified
                        </Badge>
                      ) : null}
                      {host.hasSshKey && host.verifyHostKey && !host.hostKey ? (
                        <Badge bg="warning-subtle" text="warning-emphasis" title="The key is recorded on the first successful backup">
                          host key pending
                        </Badge>
                      ) : null}
                      {!host.useSSL && !host.hasSshKey ? (
                        <span className="text-body-secondary">direct</span>
                      ) : null}
                    </div>
                  </td>
                  <td className="text-end">
                    <div className="btn-group btn-group-sm" role="group" aria-label={`Actions for ${host.name}`}>
                      <Button
                        variant="outline-secondary"
                        aria-label={`Edit ${host.name}`}
                        onClick={() => openEdit(host)}
                      >
                        <Pencil aria-hidden />
                      </Button>
                      <Button
                        variant="outline-danger"
                        aria-label={`Delete ${host.name}`}
                        onClick={() => setToDelete(host)}
                      >
                        <Trash aria-hidden />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      <Modal show={dialogOpen} onHide={closeDialog} centered scrollable aria-labelledby="host-dialog-title">
        <Modal.Header closeButton>
          <div>
            <Modal.Title id="host-dialog-title" className="fs-5">
              {editing ? `Edit ${editing.name}` : 'Add host'}
            </Modal.Title>
            <p className="mb-0 small text-body-secondary">
              {editing
                ? 'Connection details for this server.'
                : 'The server a database runs on. SSH fields are only needed for remote dumps over SSH.'}
            </p>
          </div>
        </Modal.Header>
        <Form onSubmit={onSubmit} noValidate>
          <Modal.Body>
            <div className="vstack gap-3">
              <FormField id="host-hostname" label="Hostname" error={errors.hostname}>
                <Form.Control
                  id="host-hostname"
                  required
                  placeholder="db.example.com"
                  value={form.hostname}
                  isInvalid={!!errors.hostname}
                  onChange={(e) => set('hostname', e.target.value)}
                />
              </FormField>
              <div className="row g-3">
                <div className="col-6">
                  <FormField
                    id="host-friendlyName"
                    label="Display name"
                    error={errors.friendlyName}
                    description="Optional; shown instead of the hostname."
                  >
                    <Form.Control
                      id="host-friendlyName"
                      value={form.friendlyName}
                      isInvalid={!!errors.friendlyName}
                      onChange={(e) => set('friendlyName', e.target.value)}
                    />
                  </FormField>
                </div>
                <div className="col-6">
                  <FormField id="host-port" label="Port" error={errors.port} description="Empty uses 3306.">
                    <Form.Control
                      id="host-port"
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={65535}
                      placeholder="3306"
                      value={form.port}
                      isInvalid={!!errors.port}
                      onChange={(e) => set('port', e.target.value)}
                    />
                  </FormField>
                </div>
              </div>
              <Form.Check
                type="checkbox"
                id="host-useSSL"
                label="Connect with SSL"
                checked={form.useSSL}
                onChange={(e) => set('useSSL', e.target.checked)}
              />

              <fieldset className="border rounded p-3">
                <legend className="float-none w-auto px-1 fs-6 small fw-medium text-body-secondary">
                  SSH access (optional)
                </legend>
                <div className="vstack gap-3">
                  <div className="row g-3">
                    <div className="col-6">
                      <FormField id="host-sshHostname" label="SSH host" error={errors.sshHostname}>
                        <Form.Control
                          id="host-sshHostname"
                          value={form.sshHostname}
                          onChange={(e) => set('sshHostname', e.target.value)}
                        />
                      </FormField>
                    </div>
                    <div className="col-6">
                      <FormField id="host-sshPort" label="SSH port" error={errors.sshPort}>
                        <Form.Control
                          id="host-sshPort"
                          type="number"
                          inputMode="numeric"
                          min={1}
                          max={65535}
                          placeholder="22"
                          value={form.sshPort}
                          onChange={(e) => set('sshPort', e.target.value)}
                        />
                      </FormField>
                    </div>
                  </div>
                  <FormField id="host-sshUser" label="SSH user" error={errors.sshUser}>
                    <Form.Control id="host-sshUser" value={form.sshUser} onChange={(e) => set('sshUser', e.target.value)} />
                  </FormField>

                  <div>
                    <Form.Check
                      type="checkbox"
                      id="host-verifyHostKey"
                      label="Verify the host key"
                      checked={form.verifyHostKey}
                      onChange={(e) => set('verifyHostKey', e.target.checked)}
                    />
                    <p className="small text-body-secondary mb-0 mt-1">
                      {form.verifyHostKey
                        ? form.hostKey
                          ? 'The host must present exactly this key. Clear the field below to forget it and learn it again.'
                          : 'The first successful backup records the key it sees; every later one must match it.'
                        : 'Without this, anything that can answer for the SSH host receives the database password.'}
                    </p>
                  </div>
                  {form.verifyHostKey ? (
                    <FormField
                      id="host-hostKey"
                      label="Pinned host key"
                      error={errors.hostKey}
                      description="known_hosts format. Leave empty to learn it on the first connection, or paste the output of ssh-keyscan to pin it up front."
                    >
                      <Form.Control
                        as="textarea"
                        id="host-hostKey"
                        rows={3}
                        className="font-monospace small"
                        placeholder="db.example.com ssh-ed25519 AAAAC3Nza…"
                        value={form.hostKey}
                        isInvalid={!!errors.hostKey}
                        onChange={(e) => set('hostKey', e.target.value)}
                      />
                    </FormField>
                  ) : null}
                  <FormField
                    id="host-sshKey"
                    label="SSH private key"
                    error={errors.sshKey}
                    description={
                      editing?.hasSshKey
                        ? 'A key is stored. Leave empty to keep it; paste a new key to replace it.'
                        : 'Stored encrypted and never shown again.'
                    }
                  >
                    <Form.Control
                      as="textarea"
                      id="host-sshKey"
                      rows={4}
                      className="font-monospace small"
                      placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                      value={form.sshKey}
                      isInvalid={!!errors.sshKey}
                      onChange={(e) => set('sshKey', e.target.value)}
                    />
                  </FormField>
                </div>
              </fieldset>
            </div>
          </Modal.Body>
          <Modal.Footer>
            <Button type="button" variant="secondary" onClick={closeDialog} disabled={saveMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={saveMutation.isPending || !form.hostname.trim()}>
              {saveMutation.isPending ? 'Saving…' : editing ? 'Save host' : 'Add host'}
            </Button>
          </Modal.Footer>
        </Form>
      </Modal>

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title={toDelete ? `Delete ${toDelete.name}?` : ''}
        description={
          toDelete ? (
            <HostDeleteWarning host={toDelete} impact={deletionImpact.data} loading={deletionImpact.isPending} />
          ) : (
            ''
          )
        }
        confirmLabel="Delete host"
        pending={deleteMutation.isPending || deletionImpact.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete)}
      />
    </>
  )
}

/**
 * Deleting a host cascades to every database on it and all of their
 * archives, which is far more than the wording "delete host" suggests — so
 * the databases are named and the backup count is stated outright.
 */
function HostDeleteWarning({
  host,
  impact,
  loading,
}: {
  host: Host
  impact: Host | undefined
  loading: boolean
}) {
  if (loading) {
    return <>Checking what depends on {host.name}…</>
  }

  const databaseNames = impact?.databaseNames ?? []
  const backupCount = impact?.backupCount ?? 0

  if (!databaseNames.length) {
    return <>No databases use this host. Its connection details and stored SSH key are removed.</>
  }

  return (
    <>
      <strong className="text-danger">
        {databaseNames.length} database{databaseNames.length === 1 ? '' : 's'}
        {backupCount ? ` and ${backupCount} backup${backupCount === 1 ? '' : 's'}` : ''}
      </strong>{' '}
      are permanently deleted along with this host
      {backupCount ? ', including every stored archive' : ''}. Affected: {databaseNames.join(', ')}. This
      cannot be undone.
    </>
  )
}
