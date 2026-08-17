import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Badge from 'react-bootstrap/Badge'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'
import Modal from 'react-bootstrap/Modal'
import Table from 'react-bootstrap/Table'
import { Clipboard, ClipboardCheck, Key, Trash } from 'react-bootstrap-icons'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { parseFailure, type FieldErrors } from '@/api/helpers'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { FormField } from '@/components/shared/FormField'
import { PageHeader } from '@/components/shared/PageHeader'
import { Skeleton } from '@/components/shared/Skeleton'
import { copyText } from '@/lib/clipboard'
import { formatDateTime } from '@/lib/format'
import { toast } from '@/lib/toast'

type ApiKey = components['schemas']['ApiKey']

export function ApiKeysPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [comment, setComment] = useState('')
  const [validUntil, setValidUntil] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [createdKey, setCreatedKey] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [toDelete, setToDelete] = useState<ApiKey | null>(null)
  const [databaseIds, setDatabaseIds] = useState<string[]>([])

  const keys = useQuery({
    queryKey: ['api-keys'],
    queryFn: async () => (await client.GET('/api/v1/api-keys')).data,
  })

  const databases = useQuery({
    queryKey: ['databases'],
    queryFn: async () => (await client.GET('/api/v1/databases', { params: { query: { pageSize: 200 } } })).data,
    staleTime: 60_000,
  })

  const createMutation = useMutation({
    mutationFn: async () => {
      const result = await client.POST('/api/v1/api-keys', {
        body: {
          comment: comment.trim() || null,
          validUntil: validUntil ? new Date(validUntil).toISOString() : null,
          databaseIds,
        },
      })
      if (result.error || !result.response.ok) throw parseFailure(result.error)
      return result.data
    },
    onSuccess: (data) => {
      setCreateOpen(false)
      setComment('')
      setValidUntil('')
      setDatabaseIds([])
      setCreatedKey(data?.data?.key ?? null)
      setCopied(false)
      void queryClient.invalidateQueries({ queryKey: ['api-keys'] })
    },
    onError: (failure: unknown) => {
      const { message, fields } = failure as ReturnType<typeof parseFailure>
      setErrors(fields ?? {})
      if (!fields || !Object.keys(fields).length) toast.error(message)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: async (apiKey: ApiKey) => {
      const { response } = await client.DELETE('/api/v1/api-keys/{id}', {
        params: { path: { id: apiKey.id! } },
      })
      if (!response.ok) throw new Error(String(response.status))
    },
    onSuccess: () => {
      toast.success('API key deleted — clients using it lose access now')
      setToDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['api-keys'] })
    },
    onError: () => toast.error('The API key could not be deleted. Try again.'),
  })

  async function copyKey() {
    if (!createdKey) return
    if (await copyText(createdKey)) {
      setCopied(true)
    } else {
      // this is the one dialog the key is ever shown in, so a silent
      // failure would lose it — say so and let the user select it manually
      toast.error('Could not copy automatically. Select the key and copy it manually.')
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    createMutation.mutate()
  }

  const rows = keys.data?.data ?? []

  return (
    <>
      <PageHeader
        title="API keys"
        description="Machine access for triggering backups via the HTTP API (X-API-Key header)."
        action={<Button onClick={() => setCreateOpen(true)}>Create key</Button>}
      />

      {keys.isPending ? (
        <Skeleton height="16rem" />
      ) : keys.isError ? (
        <ErrorState message="API keys could not be loaded." onRetry={() => keys.refetch()} />
      ) : !rows.length ? (
        <EmptyState
          icon={Key}
          title="No API keys"
          description="Create a key to let scripts or CI jobs queue backups through the API."
          action={<Button onClick={() => setCreateOpen(true)}>Create key</Button>}
        />
      ) : (
        <Card>
          <Table hover responsive className="mb-0 align-middle">
            <thead>
              <tr>
                <th scope="col">Key</th>
                <th scope="col">Comment</th>
                <th scope="col">Scope</th>
                <th scope="col">Created</th>
                <th scope="col">Expires</th>
                <th scope="col">Last used</th>
                <th scope="col" className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((apiKey) => (
                <tr key={apiKey.id}>
                  <td className="font-monospace small">
                    {apiKey.keyHint ? `${apiKey.keyHint}…` : '••••••••'}
                    {!apiKey.isValid ? (
                      <Badge bg="danger" className="ms-2">
                        Expired
                      </Badge>
                    ) : null}
                  </td>
                  <td>{apiKey.comment ?? <span className="text-body-secondary">—</span>}</td>
                  <td>
                    {apiKey.databaseNames?.length ? (
                      <span title={apiKey.databaseNames.join(', ')}>
                        {apiKey.databaseNames.length === 1
                          ? apiKey.databaseNames[0]
                          : `${apiKey.databaseNames.length} databases`}
                      </span>
                    ) : (
                      <Badge bg="secondary-subtle" text="secondary-emphasis" className="border">
                        All databases
                      </Badge>
                    )}
                  </td>
                  <td>{formatDateTime(apiKey.createdAt)}</td>
                  <td>{apiKey.validUntil ? formatDateTime(apiKey.validUntil) : 'Never'}</td>
                  <td>{formatDateTime(apiKey.lastConnectedAt)}</td>
                  <td className="text-end">
                    <Button
                      variant="outline-danger"
                      size="sm"
                      aria-label={`Delete key ${apiKey.keyHint ?? apiKey.id}`}
                      onClick={() => setToDelete(apiKey)}
                    >
                      <Trash aria-hidden />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      <Modal show={createOpen} onHide={() => setCreateOpen(false)} centered aria-labelledby="api-key-dialog-title">
        <Modal.Header closeButton>
          <div>
            <Modal.Title id="api-key-dialog-title" className="fs-5">
              Create API key
            </Modal.Title>
            <p className="mb-0 small text-body-secondary">
              The key is shown once after creation — store it somewhere safe.
            </p>
          </div>
        </Modal.Header>
        <Form onSubmit={onSubmit} noValidate>
          <Modal.Body>
            <div className="vstack gap-3">
              <FormField
                id="key-comment"
                label="Comment"
                error={errors.comment}
                description="What this key is for, e.g. “nightly CI backup”."
              >
                <Form.Control
                  id="key-comment"
                  value={comment}
                  isInvalid={!!errors.comment}
                  onChange={(e) => setComment(e.target.value)}
                />
              </FormField>
              <FormField
                id="key-validUntil"
                label="Expires"
                error={errors.validUntil}
                description="Empty means the key never expires."
              >
                <Form.Control
                  id="key-validUntil"
                  type="datetime-local"
                  value={validUntil}
                  isInvalid={!!errors.validUntil}
                  onChange={(e) => setValidUntil(e.target.value)}
                />
              </FormField>

              <fieldset>
                <legend className="fs-6 small fw-medium">Databases this key may back up</legend>
                <p className="small text-body-secondary mb-2">
                  {databaseIds.length
                    ? `Limited to ${databaseIds.length} database${databaseIds.length === 1 ? '' : 's'}.`
                    : 'Nothing selected — the key may back up every database, including ones added later.'}
                </p>
                <div className="vstack gap-2 border rounded p-3 overflow-y-auto" style={{ maxHeight: '12rem' }}>
                  {(databases.data?.data ?? []).map((database) => (
                    <Form.Check
                      key={database.id}
                      type="checkbox"
                      id={`key-db-${database.id}`}
                      label={database.name}
                      checked={databaseIds.includes(database.id!)}
                      onChange={(e) =>
                        setDatabaseIds((current) =>
                          e.target.checked
                            ? [...current, database.id!]
                            : current.filter((id) => id !== database.id),
                        )
                      }
                    />
                  ))}
                  {!databases.data?.data?.length ? (
                    <p className="small text-body-secondary mb-0">No databases configured yet.</p>
                  ) : null}
                </div>
              </fieldset>
            </div>
          </Modal.Body>
          <Modal.Footer>
            <Button type="button" variant="secondary" onClick={() => setCreateOpen(false)} disabled={createMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating…' : 'Create key'}
            </Button>
          </Modal.Footer>
        </Form>
      </Modal>

      <Modal
        show={createdKey !== null}
        onHide={() => setCreatedKey(null)}
        centered
        aria-labelledby="api-key-created-title"
      >
        <Modal.Header closeButton>
          <div>
            <Modal.Title id="api-key-created-title" className="fs-5">
              Key created
            </Modal.Title>
            <p className="mb-0 small text-body-secondary">
              Copy it now — for security it is stored hashed and cannot be shown again.
            </p>
          </div>
        </Modal.Header>
        <Modal.Body>
          <div className="d-flex align-items-center gap-2">
            <code className="flex-grow-1 border rounded bg-body-tertiary p-3 font-monospace small text-break">
              {createdKey}
            </code>
            <Button variant="outline-secondary" aria-label="Copy key" onClick={() => void copyKey()}>
              {copied ? <ClipboardCheck aria-hidden /> : <Clipboard aria-hidden />}
            </Button>
          </div>
        </Modal.Body>
        <Modal.Footer>
          <Button onClick={() => setCreatedKey(null)}>Done</Button>
        </Modal.Footer>
      </Modal>

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title="Delete this API key?"
        description={`Clients authenticating with ${toDelete?.keyHint ?? 'this'}… stop working immediately. This cannot be undone.`}
        confirmLabel="Delete key"
        pending={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete)}
      />
    </>
  )
}
