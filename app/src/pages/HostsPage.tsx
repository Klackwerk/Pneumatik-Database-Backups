import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Server, Trash2 } from 'lucide-react'
import { toast } from 'sonner'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
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
import { Textarea } from '@/components/ui/textarea'

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
        <Skeleton className="h-64 w-full" />
      ) : hosts.isError ? (
        <ErrorState message="Hosts could not be loaded." onRetry={() => hosts.refetch()} />
      ) : !rows.length ? (
        <EmptyState
          icon={Server}
          title="No hosts yet"
          description="Add the server a database runs on. Databases reference a host for connection details."
          action={<Button onClick={openAdd}>Add host</Button>}
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Address</TableHead>
                <TableHead>Connection</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((host) => (
                <TableRow key={host.id}>
                  <TableCell className="font-medium">{host.name}</TableCell>
                  <TableCell className="tabular-nums">
                    {host.hostname}:{host.port}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1.5">
                      {host.useSSL ? <Badge variant="secondary">SSL</Badge> : null}
                      {host.hasSshKey ? <Badge variant="secondary">via SSH</Badge> : null}
                      {host.hasSshKey && !host.verifyHostKey ? (
                        <Badge variant="destructive" title="The SSH host key is not verified">
                          host key unverified
                        </Badge>
                      ) : null}
                      {host.hasSshKey && host.verifyHostKey && !host.hostKey ? (
                        <Badge variant="outline" title="The key is recorded on the first successful backup">
                          host key pending
                        </Badge>
                      ) : null}
                      {!host.useSSL && !host.hasSshKey ? (
                        <span className="text-muted-foreground">direct</span>
                      ) : null}
                    </div>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="inline-flex gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Edit ${host.name}`}
                        onClick={() => openEdit(host)}
                      >
                        <Pencil className="size-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Delete ${host.name}`}
                        onClick={() => setToDelete(host)}
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={(open) => (open ? setDialogOpen(true) : closeDialog())}>
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? `Edit ${editing.name}` : 'Add host'}</DialogTitle>
            <DialogDescription>
              {editing
                ? 'Connection details for this server.'
                : 'The server a database runs on. SSH fields are only needed for remote dumps over SSH.'}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={onSubmit} noValidate>
            <FieldGroup>
              <FormField id="host-hostname" label="Hostname" error={errors.hostname}>
                <Input
                  id="host-hostname"
                  required
                  placeholder="db.example.com"
                  value={form.hostname}
                  aria-invalid={!!errors.hostname}
                  onChange={(e) => set('hostname', e.target.value)}
                />
              </FormField>
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  id="host-friendlyName"
                  label="Display name"
                  error={errors.friendlyName}
                  description="Optional; shown instead of the hostname."
                >
                  <Input
                    id="host-friendlyName"
                    value={form.friendlyName}
                    aria-invalid={!!errors.friendlyName}
                    onChange={(e) => set('friendlyName', e.target.value)}
                  />
                </FormField>
                <FormField id="host-port" label="Port" error={errors.port} description="Empty uses 3306.">
                  <Input
                    id="host-port"
                    type="number"
                    inputMode="numeric"
                    min={1}
                    max={65535}
                    placeholder="3306"
                    value={form.port}
                    aria-invalid={!!errors.port}
                    onChange={(e) => set('port', e.target.value)}
                  />
                </FormField>
              </div>
              <FieldLabel className="gap-2">
                <Checkbox checked={form.useSSL} onCheckedChange={(checked) => set('useSSL', checked === true)} />
                Connect with SSL
              </FieldLabel>

              <fieldset className="rounded-lg border p-4">
                <legend className="px-1 text-sm font-medium text-muted-foreground">SSH access (optional)</legend>
                <FieldGroup>
                  <div className="grid grid-cols-2 gap-4">
                    <FormField id="host-sshHostname" label="SSH host" error={errors.sshHostname}>
                      <Input
                        id="host-sshHostname"
                        value={form.sshHostname}
                        onChange={(e) => set('sshHostname', e.target.value)}
                      />
                    </FormField>
                    <FormField id="host-sshPort" label="SSH port" error={errors.sshPort}>
                      <Input
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
                  <FormField id="host-sshUser" label="SSH user" error={errors.sshUser}>
                    <Input id="host-sshUser" value={form.sshUser} onChange={(e) => set('sshUser', e.target.value)} />
                  </FormField>

                  <FieldLabel className="gap-2">
                    <Checkbox
                      checked={form.verifyHostKey}
                      onCheckedChange={(checked) => set('verifyHostKey', checked === true)}
                    />
                    Verify the host key
                  </FieldLabel>
                  <p className="-mt-2 text-xs text-muted-foreground">
                    {form.verifyHostKey
                      ? form.hostKey
                        ? 'The host must present exactly this key. Clear the field below to forget it and learn it again.'
                        : 'The first successful backup records the key it sees; every later one must match it.'
                      : 'Without this, anything that can answer for the SSH host receives the database password.'}
                  </p>
                  {form.verifyHostKey ? (
                    <FormField
                      id="host-hostKey"
                      label="Pinned host key"
                      error={errors.hostKey}
                      description="known_hosts format. Leave empty to learn it on the first connection, or paste the output of ssh-keyscan to pin it up front."
                    >
                      <Textarea
                        id="host-hostKey"
                        rows={3}
                        className="font-mono text-xs"
                        placeholder="db.example.com ssh-ed25519 AAAAC3Nza…"
                        value={form.hostKey}
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
                    <Textarea
                      id="host-sshKey"
                      rows={4}
                      className="font-mono text-xs"
                      placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                      value={form.sshKey}
                      onChange={(e) => set('sshKey', e.target.value)}
                    />
                  </FormField>
                </FieldGroup>
              </fieldset>
            </FieldGroup>
            <DialogFooter className="mt-6">
              <Button type="button" variant="outline" onClick={closeDialog} disabled={saveMutation.isPending}>
                Cancel
              </Button>
              <Button type="submit" disabled={saveMutation.isPending || !form.hostname.trim()}>
                {saveMutation.isPending ? 'Saving…' : editing ? 'Save host' : 'Add host'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

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
      <strong className="text-destructive">
        {databaseNames.length} database{databaseNames.length === 1 ? '' : 's'}
        {backupCount ? ` and ${backupCount} backup${backupCount === 1 ? '' : 's'}` : ''}
      </strong>{' '}
      are permanently deleted along with this host
      {backupCount ? ', including every stored archive' : ''}. Affected: {databaseNames.join(', ')}. This
      cannot be undone.
    </>
  )
}
