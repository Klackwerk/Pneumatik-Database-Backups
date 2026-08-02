import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArrowDown, ArrowUp, Download, Trash2 } from 'lucide-react'
import { toast } from 'sonner'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { BackupDetailDialog } from '@/components/backups/BackupDetailDialog'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { BackupStateBadge } from '@/components/backups/BackupStateBadge'
import { formatDateTime, triggerLabels } from '@/lib/format'

type Backup = components['schemas']['Backup']
type SortField = 'createdAt' | 'executedAt' | 'state' | 'size' | 'success' | 'name'
type BackupStateFilter = NonNullable<components['schemas']['BackupState']> | typeof ALL

const PAGE_SIZE = 25
/** Radix Select has no value for "no selection", so the filters name it */
const ALL = 'all'

const stateOptions: { value: BackupStateFilter; label: string }[] = [
  { value: ALL, label: 'Any status' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'FINISHED', label: 'Finished' },
  { value: 'CREATED', label: 'Queued' },
  { value: 'RUNNING', label: 'Running' },
]

const sortableColumns: { field: SortField; label: string }[] = [
  { field: 'name', label: 'Database' },
  { field: 'createdAt', label: 'Created' },
  { field: 'executedAt', label: 'Executed' },
  { field: 'state', label: 'Status' },
  { field: 'size', label: 'Size' },
]

/**
 * Hands the download to the browser instead of fetching it ourselves.
 *
 * Reading the response into a blob first would hold the entire archive in
 * memory — these are database dumps, routinely gigabytes — and shows no
 * progress. A navigation cannot carry an Authorization header, so we trade
 * the bearer token for a single-use ticket and let the browser's own
 * download manager stream it to disk.
 */
async function downloadBackup(backup: Backup) {
  const { data, error } = await client.POST('/api/v1/backups/{id}/download-token', {
    params: { path: { id: backup.id! } },
  })
  const token = data?.data?.token
  if (!token) {
    throw error ?? new Error('no download ticket')
  }

  const link = document.createElement('a')
  link.href = `/api/v1/backups/${backup.id}/download?token=${encodeURIComponent(token)}`
  // the server sets Content-Disposition; this is only the fallback name
  link.download = backup.filename ?? `backup-${backup.id}.zip`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

export function BackupsPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [page, setPage] = useState(1)
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<SortField>('createdAt')
  const [order, setOrder] = useState<'asc' | 'desc'>('desc')
  const [toDelete, setToDelete] = useState<Backup | null>(null)
  const [downloading, setDownloading] = useState<string | null>(null)
  const [detailId, setDetailId] = useState<string | null>(null)

  // filters live in the URL so the dashboard can link straight to
  // "the failures" and the view survives a reload or a shared link
  const state = (searchParams.get('state') ?? ALL) as BackupStateFilter
  const databaseId = searchParams.get('databaseId') ?? ALL
  const hostId = searchParams.get('hostId') ?? ALL

  function setFilter(key: 'state' | 'databaseId' | 'hostId', value: string) {
    const next = new URLSearchParams(searchParams)
    if (value === ALL) {
      next.delete(key)
    } else {
      next.set(key, value)
    }
    // a host filter and a database filter would contradict each other
    if (key === 'hostId') next.delete('databaseId')
    if (key === 'databaseId') next.delete('hostId')
    setSearchParams(next, { replace: true })
    setPage(1)
  }

  // debounce the search box; reset to page 1 on new search
  useEffect(() => {
    const handle = setTimeout(() => {
      setSearch(searchInput)
      setPage(1)
    }, 300)
    return () => clearTimeout(handle)
  }, [searchInput])

  const databases = useQuery({
    queryKey: ['databases'],
    queryFn: async () => (await client.GET('/api/v1/databases', { params: { query: { pageSize: 200 } } })).data,
    staleTime: 60_000,
  })

  const hosts = useQuery({
    queryKey: ['hosts'],
    queryFn: async () => (await client.GET('/api/v1/hosts', { params: { query: { pageSize: 200 } } })).data,
    staleTime: 60_000,
  })

  const backups = useQuery({
    queryKey: ['backups', { page, search, sort, order, state, databaseId, hostId }],
    queryFn: async () =>
      (
        await client.GET('/api/v1/backups', {
          params: {
            query: {
              page,
              pageSize: PAGE_SIZE,
              search: search || undefined,
              sort,
              order,
              state: state === ALL ? undefined : state,
              databaseId: databaseId === ALL ? undefined : databaseId,
              hostId: hostId === ALL ? undefined : hostId,
            },
          },
        })
      ).data,
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  })

  const filtered = state !== ALL || databaseId !== ALL || hostId !== ALL || !!search

  const deleteMutation = useMutation({
    mutationFn: async (backup: Backup) => {
      const { response } = await client.DELETE('/api/v1/backups/{id}', {
        params: { path: { id: backup.id! } },
      })
      if (!response.ok) throw new Error(String(response.status))
    },
    onSuccess: (_, backup) => {
      toast.success(`Backup ${backup.filename ?? backup.id} deleted`)
      setToDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['backups'] })
    },
    onError: () => toast.error('The backup could not be deleted. Try again.'),
  })

  function toggleSort(field: SortField) {
    if (sort === field) {
      setOrder(order === 'asc' ? 'desc' : 'asc')
    } else {
      setSort(field)
      setOrder(field === 'name' ? 'asc' : 'desc')
    }
    setPage(1)
  }

  async function onDownload(backup: Backup) {
    setDownloading(backup.id!)
    try {
      await downloadBackup(backup)
    } catch {
      toast.error('The backup file could not be downloaded from storage.')
    } finally {
      setDownloading(null)
    }
  }

  const rows = backups.data?.data ?? []
  const total = backups.data?.meta?.filtered ?? backups.data?.meta?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <>
      <PageHeader title="Backups" description="Every backup run, newest first. Finished backups can be downloaded." />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input
          type="search"
          placeholder="Filter by database name"
          aria-label="Filter backups by database name"
          className="max-w-xs"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />

        <Select value={state} onValueChange={(value) => setFilter('state', value)}>
          <SelectTrigger className="w-[160px]" aria-label="Filter backups by status">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {stateOptions.map(({ value, label }) => (
              <SelectItem key={value} value={value}>
                {label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={databaseId} onValueChange={(value) => setFilter('databaseId', value)}>
          <SelectTrigger className="w-[200px]" aria-label="Filter backups by database">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>Any database</SelectItem>
            {(databases.data?.data ?? []).map((database) => (
              <SelectItem key={database.id} value={database.id!}>
                {database.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={hostId} onValueChange={(value) => setFilter('hostId', value)}>
          <SelectTrigger className="w-[200px]" aria-label="Filter backups by host">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>Any host</SelectItem>
            {(hosts.data?.data ?? []).map((host) => (
              <SelectItem key={host.id} value={host.id!}>
                {host.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {filtered ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setSearchInput('')
              setSearchParams(new URLSearchParams(), { replace: true })
              setPage(1)
            }}
          >
            Clear filters
          </Button>
        ) : null}
      </div>

      {backups.isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : backups.isError ? (
        <ErrorState message="Backups could not be loaded." onRetry={() => backups.refetch()} />
      ) : !rows.length && !filtered ? (
        <EmptyState
          icon={Archive}
          title="No backups yet"
          description="Backups appear here once a schedule fires or you trigger one from the Databases page."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  {sortableColumns.map(({ field, label }) => (
                    <TableHead key={field} aria-sort={sort === field ? (order === 'asc' ? 'ascending' : 'descending') : undefined}>
                      <button
                        type="button"
                        onClick={() => toggleSort(field)}
                        className="inline-flex items-center gap-1 font-medium hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
                      >
                        {label}
                        {sort === field ? (
                          order === 'asc' ? (
                            <ArrowUp className="size-3.5" aria-hidden />
                          ) : (
                            <ArrowDown className="size-3.5" aria-hidden />
                          )
                        ) : null}
                      </button>
                    </TableHead>
                  ))}
                  <TableHead>Triggered by</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.length ? (
                  rows.map((backup) => (
                    <TableRow key={backup.id} className="cursor-pointer" onClick={() => setDetailId(backup.id ?? null)}>
                      {/*
                        The row reacts to a click as a convenience, but the
                        name is the real control: a <tr> with a tabIndex is
                        announced as a table row, not as something that can be
                        activated, and it never appears in a list of buttons.
                      */}
                      <TableCell className="font-medium">
                        <button
                          type="button"
                          className="text-left hover:underline focus-visible:outline-2 focus-visible:outline-ring"
                          onClick={(e) => {
                            e.stopPropagation()
                            setDetailId(backup.id ?? null)
                          }}
                        >
                          {backup.databaseName}
                          <span className="sr-only"> — show backup details</span>
                        </button>
                      </TableCell>
                      <TableCell>{formatDateTime(backup.createdAt)}</TableCell>
                      <TableCell>{formatDateTime(backup.executedAt)}</TableCell>
                      <TableCell>
                        <BackupStateBadge state={backup.state ?? null} />
                      </TableCell>
                      <TableCell className="tabular-nums">{backup.size ?? '—'}</TableCell>
                      <TableCell className="text-muted-foreground">
                        {backup.createdBy ?? (backup.trigger ? (triggerLabels[backup.trigger] ?? backup.trigger) : 'Schedule')}
                      </TableCell>
                      <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                        <div className="inline-flex gap-1">
                          <Button
                            variant="ghost"
                            size="icon"
                            aria-label={`Download ${backup.filename ?? `backup ${backup.id}`}`}
                            disabled={backup.state !== 'FINISHED' || downloading === backup.id}
                            onClick={() => void onDownload(backup)}
                          >
                            <Download className="size-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            aria-label={`Delete ${backup.filename ?? `backup ${backup.id}`}`}
                            onClick={() => setToDelete(backup)}
                          >
                            <Trash2 className="size-4" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))
                ) : (
                  <TableRow>
                    <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                      No backups match the current filters.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>

          <div className="mt-4 flex items-center justify-between text-sm text-muted-foreground">
            <span>
              {total} backup{total === 1 ? '' : 's'}
              {filtered ? ' matching filters' : ''}
            </span>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
                Previous
              </Button>
              <span className="tabular-nums">
                Page {page} of {pageCount}
              </span>
              <Button variant="outline" size="sm" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>
                Next
              </Button>
            </div>
          </div>
        </>
      )}

      <BackupDetailDialog backupId={detailId} onOpenChange={(open) => !open && setDetailId(null)} />

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => !open && setToDelete(null)}
        title="Delete this backup?"
        description={
          toDelete
            ? `${toDelete.filename ?? `Backup ${toDelete.id}`} and its stored archive are removed permanently. This cannot be undone.`
            : ''
        }
        confirmLabel="Delete backup"
        pending={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete)}
      />
    </>
  )
}
