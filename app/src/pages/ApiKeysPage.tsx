import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy, KeyRound, Trash2 } from 'lucide-react'
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Checkbox } from '@/components/ui/checkbox'
import { FieldGroup, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { copyText } from '@/lib/clipboard'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatDateTime } from '@/lib/format'

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
        <Skeleton className="h-64 w-full" />
      ) : keys.isError ? (
        <ErrorState message="API keys could not be loaded." onRetry={() => keys.refetch()} />
      ) : !rows.length ? (
        <EmptyState
          icon={KeyRound}
          title="No API keys"
          description="Create a key to let scripts or CI jobs queue backups through the API."
          action={<Button onClick={() => setCreateOpen(true)}>Create key</Button>}
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Key</TableHead>
                <TableHead>Comment</TableHead>
                <TableHead>Scope</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Expires</TableHead>
                <TableHead>Last used</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((apiKey) => (
                <TableRow key={apiKey.id}>
                  <TableCell className="font-mono text-xs">
                    {apiKey.keyHint ? `${apiKey.keyHint}…` : '••••••••'}
                    {!apiKey.isValid ? (
                      <Badge variant="destructive" className="ml-2">
                        Expired
                      </Badge>
                    ) : null}
                  </TableCell>
                  <TableCell>{apiKey.comment ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell>
                    {apiKey.databaseNames?.length ? (
                      <span title={apiKey.databaseNames.join(', ')}>
                        {apiKey.databaseNames.length === 1
                          ? apiKey.databaseNames[0]
                          : `${apiKey.databaseNames.length} databases`}
                      </span>
                    ) : (
                      <Badge variant="outline">All databases</Badge>
                    )}
                  </TableCell>
                  <TableCell>{formatDateTime(apiKey.createdAt)}</TableCell>
                  <TableCell>{apiKey.validUntil ? formatDateTime(apiKey.validUntil) : 'Never'}</TableCell>
                  <TableCell>{formatDateTime(apiKey.lastConnectedAt)}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label={`Delete key ${apiKey.keyHint ?? apiKey.id}`}
                      onClick={() => setToDelete(apiKey)}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Create API key</DialogTitle>
            <DialogDescription>The key is shown once after creation — store it somewhere safe.</DialogDescription>
          </DialogHeader>
          <form onSubmit={onSubmit} noValidate>
            <FieldGroup>
              <FormField
                id="key-comment"
                label="Comment"
                error={errors.comment}
                description="What this key is for, e.g. “nightly CI backup”."
              >
                <Input id="key-comment" value={comment} onChange={(e) => setComment(e.target.value)} />
              </FormField>
              <FormField
                id="key-validUntil"
                label="Expires"
                error={errors.validUntil}
                description="Empty means the key never expires."
              >
                <Input
                  id="key-validUntil"
                  type="datetime-local"
                  value={validUntil}
                  onChange={(e) => setValidUntil(e.target.value)}
                />
              </FormField>

              <fieldset>
                <legend className="text-sm font-medium">Databases this key may back up</legend>
                <p className="mb-2 text-xs text-muted-foreground">
                  {databaseIds.length
                    ? `Limited to ${databaseIds.length} database${databaseIds.length === 1 ? '' : 's'}.`
                    : 'Nothing selected — the key may back up every database, including ones added later.'}
                </p>
                <div className="flex max-h-48 flex-col gap-2 overflow-y-auto rounded-lg border p-3">
                  {(databases.data?.data ?? []).map((database) => (
                    <FieldLabel key={database.id} className="gap-2">
                      <Checkbox
                        checked={databaseIds.includes(database.id!)}
                        onCheckedChange={(checked) =>
                          setDatabaseIds((current) =>
                            checked === true
                              ? [...current, database.id!]
                              : current.filter((id) => id !== database.id),
                          )
                        }
                      />
                      {database.name}
                    </FieldLabel>
                  ))}
                  {!databases.data?.data?.length ? (
                    <p className="text-xs text-muted-foreground">No databases configured yet.</p>
                  ) : null}
                </div>
              </fieldset>
            </FieldGroup>
            <DialogFooter className="mt-6">
              <Button type="button" variant="outline" onClick={() => setCreateOpen(false)} disabled={createMutation.isPending}>
                Cancel
              </Button>
              <Button type="submit" disabled={createMutation.isPending}>
                {createMutation.isPending ? 'Creating…' : 'Create key'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={createdKey !== null} onOpenChange={(open) => !open && setCreatedKey(null)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Key created</DialogTitle>
            <DialogDescription>
              Copy it now — for security it is stored hashed and cannot be shown again.
            </DialogDescription>
          </DialogHeader>
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 break-all rounded-md bg-muted p-3 font-mono text-xs">{createdKey}</code>
            <Button variant="outline" size="icon" aria-label="Copy key" onClick={() => void copyKey()}>
              {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
            </Button>
          </div>
          <DialogFooter>
            <Button onClick={() => setCreatedKey(null)}>Done</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

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
