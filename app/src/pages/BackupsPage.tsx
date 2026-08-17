import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Form from 'react-bootstrap/Form'
import Table from 'react-bootstrap/Table'
import { Archive, ArrowDown, ArrowUp, Download, Trash } from 'react-bootstrap-icons'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { BackupDetailDialog } from '@/components/backups/BackupDetailDialog'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Skeleton } from '@/components/shared/Skeleton'
import { BackupStateBadge } from '@/components/backups/BackupStateBadge'
import { formatDateTime, triggerLabels } from '@/lib/format'
import { toast } from '@/lib/toast'

type Backup = components['schemas']['Backup']
type SortField = 'createdAt' | 'executedAt' | 'state' | 'size' | 'success' | 'name'
type BackupStateFilter = NonNullable<components['schemas']['BackupState']> | typeof ALL

const PAGE_SIZE = 25
/** The filters name "no selection" explicitly so it can live in the URL */
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

      <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
        <Form.Control
          type="search"
          placeholder="Filter by database name"
          aria-label="Filter backups by database name"
          className="w-auto"
          style={{ maxWidth: '20rem' }}
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />

        <Form.Select
          className="w-auto"
          aria-label="Filter backups by status"
          value={state}
          onChange={(e) => setFilter('state', e.target.value)}
        >
          {stateOptions.map(({ value, label }) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </Form.Select>

        <Form.Select
          className="w-auto"
          aria-label="Filter backups by database"
          value={databaseId}
          onChange={(e) => setFilter('databaseId', e.target.value)}
        >
          <option value={ALL}>Any database</option>
          {(databases.data?.data ?? []).map((database) => (
            <option key={database.id} value={database.id!}>
              {database.name}
            </option>
          ))}
        </Form.Select>

        <Form.Select
          className="w-auto"
          aria-label="Filter backups by host"
          value={hostId}
          onChange={(e) => setFilter('hostId', e.target.value)}
        >
          <option value={ALL}>Any host</option>
          {(hosts.data?.data ?? []).map((host) => (
            <option key={host.id} value={host.id!}>
              {host.name}
            </option>
          ))}
        </Form.Select>

        {filtered ? (
          <Button
            variant="link"
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
        <Skeleton height="24rem" />
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
          <Card>
            <Table hover responsive className="mb-0 align-middle">
              <thead>
                <tr>
                  {sortableColumns.map(({ field, label }) => (
                    <th
                      key={field}
                      scope="col"
                      aria-sort={sort === field ? (order === 'asc' ? 'ascending' : 'descending') : undefined}
                    >
                      <button
                        type="button"
                        onClick={() => toggleSort(field)}
                        className="btn btn-link btn-sm p-0 fw-semibold text-decoration-none link-body-emphasis d-inline-flex align-items-center gap-1"
                      >
                        {label}
                        {sort === field ? (
                          order === 'asc' ? (
                            <ArrowUp size={14} aria-hidden />
                          ) : (
                            <ArrowDown size={14} aria-hidden />
                          )
                        ) : null}
                      </button>
                    </th>
                  ))}
                  <th scope="col">Triggered by</th>
                  <th scope="col" className="text-end">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.length ? (
                  rows.map((backup) => (
                    <tr key={backup.id} role="button" onClick={() => setDetailId(backup.id ?? null)}>
                      {/*
                        The row reacts to a click as a convenience, but the
                        name is the real control: a <tr> with a tabIndex is
                        announced as a table row, not as something that can be
                        activated, and it never appears in a list of buttons.
                      */}
                      <td className="fw-medium">
                        <button
                          type="button"
                          className="btn btn-link p-0 fw-medium text-start text-decoration-none link-body-emphasis"
                          onClick={(e) => {
                            e.stopPropagation()
                            setDetailId(backup.id ?? null)
                          }}
                        >
                          {backup.databaseName}
                          <span className="visually-hidden"> — show backup details</span>
                        </button>
                      </td>
                      <td>{formatDateTime(backup.createdAt)}</td>
                      <td>{formatDateTime(backup.executedAt)}</td>
                      <td>
                        <BackupStateBadge state={backup.state ?? null} />
                      </td>
                      <td>{backup.size ?? '—'}</td>
                      <td className="text-body-secondary">
                        {backup.createdBy ?? (backup.trigger ? (triggerLabels[backup.trigger] ?? backup.trigger) : 'Schedule')}
                      </td>
                      <td className="text-end" onClick={(e) => e.stopPropagation()}>
                        <div className="btn-group btn-group-sm" role="group" aria-label={`Actions for ${backup.filename ?? backup.id}`}>
                          <Button
                            variant="outline-secondary"
                            aria-label={`Download ${backup.filename ?? `backup ${backup.id}`}`}
                            disabled={backup.state !== 'FINISHED' || downloading === backup.id}
                            onClick={() => void onDownload(backup)}
                          >
                            <Download aria-hidden />
                          </Button>
                          <Button
                            variant="outline-danger"
                            aria-label={`Delete ${backup.filename ?? `backup ${backup.id}`}`}
                            onClick={() => setToDelete(backup)}
                          >
                            <Trash aria-hidden />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={7} className="py-4 text-center text-body-secondary">
                      No backups match the current filters.
                    </td>
                  </tr>
                )}
              </tbody>
            </Table>
          </Card>

          <div className="d-flex align-items-center justify-content-between mt-3 small text-body-secondary">
            <span>
              {total} backup{total === 1 ? '' : 's'}
              {filtered ? ' matching filters' : ''}
            </span>
            <div className="d-flex align-items-center gap-2">
              <Button variant="outline-secondary" size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
                Previous
              </Button>
              <span>
                Page {page} of {pageCount}
              </span>
              <Button variant="outline-secondary" size="sm" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>
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
