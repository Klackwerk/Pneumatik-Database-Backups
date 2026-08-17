import { Suspense, lazy, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import Button from 'react-bootstrap/Button'
import Card from 'react-bootstrap/Card'
import Table from 'react-bootstrap/Table'
import { Archive, ArrowClockwise, Database, ExclamationTriangle, Hdd, HddRack } from 'react-bootstrap-icons'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { BackupDetailDialog } from '@/components/backups/BackupDetailDialog'
import { BackupStateBadge } from '@/components/backups/BackupStateBadge'
import { ActivityLegend } from '@/components/dashboard/ActivityLegend'
import { seriesColor } from '@/components/dashboard/series'
import { ErrorState } from '@/components/shared/ErrorState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Skeleton } from '@/components/shared/Skeleton'
import { formatBytes, formatDateTime } from '@/lib/format'

type DatabaseStats = components['schemas']['DatabaseStats']

/**
 * The charting library is larger than the rest of the dashboard put together.
 * Loading it separately lets the numbers, the staleness warning and the recent
 * runs — the parts an operator actually opens this page for — paint first.
 */
const ActivityChart = lazy(() =>
  import('@/components/dashboard/ActivityChart').then((m) => ({ default: m.ActivityChart })),
)
const StorageDonut = lazy(() => import('@/components/dashboard/StorageDonut').then((m) => ({ default: m.StorageDonut })))

/**
 * Databases that are overdue for a *successful* backup.
 *
 * This is the failure mode a failure count cannot show: when backups stop
 * running at all — a schedule that never fires, a job that dies before it
 * records anything — nothing is counted as failed and the dashboard stays
 * green while the newest restorable copy silently ages.
 */
function StaleBackupsCard({ stale }: { stale: DatabaseStats[] }) {
  return (
    <Card className="mt-4 border-danger-subtle">
      <Card.Header className="d-flex align-items-center gap-2 bg-danger-subtle text-danger-emphasis">
        <ExclamationTriangle aria-hidden />
        <span className="fw-medium small">
      {stale.length === 1
        ? '1 database has no recent successful backup'
        : `${stale.length} databases have no recent successful backup`}
        </span>
      </Card.Header>
      <Card.Body>
        <ul className="list-unstyled vstack gap-2 mb-0">
          {stale.map((database) => (
            <li key={database.databaseId} className="d-flex flex-wrap align-items-baseline justify-content-between gap-2 small">
              <Link to={`/backups?databaseId=${database.databaseId}`} className="fw-medium">
                {database.databaseName}
              </Link>
              <span className="text-body-secondary">
                {database.lastSuccessfulBackupAt
                  ? `last succeeded ${formatStaleAge(database.staleSinceDays)} — ${formatDateTime(database.lastSuccessfulBackupAt)}`
                  : 'never backed up successfully'}
              </span>
            </li>
          ))}
        </ul>
      </Card.Body>
    </Card>
  )
}

function formatStaleAge(days: number | null | undefined): string {
  if (days == null) return 'a while ago'
  if (days === 0) return 'today'
  return days === 1 ? '1 day ago' : `${days} days ago`
}

function StatCard({
  title,
  value,
  icon: Icon,
  to,
  loading,
}: {
  title: string
  value: string | number | undefined
  icon: typeof Archive
  to: string
  loading: boolean
}) {
  return (
    <div className="col">
      <Link to={to} className="text-decoration-none">
        <Card className="stat-card h-100">
          <Card.Body className="p-3">
            <div className="d-flex align-items-center justify-content-between mb-2">
              <span className="small fw-medium text-body-secondary">{title}</span>
              <Icon className="text-body-secondary" aria-hidden />
            </div>
            {loading ? (
              <Skeleton height="2rem" width="3.5rem" />
            ) : (
              <p className="fs-4 fw-semibold text-body mb-0">{value ?? '—'}</p>
            )}
          </Card.Body>
        </Card>
      </Link>
    </div>
  )
}

function ChartEmpty({ children, onRetry }: { children: string; onRetry?: () => void }) {
  return (
    <div className="d-flex flex-column align-items-center justify-content-center gap-2 small text-body-secondary" style={{ height: '12rem' }}>
      <p className="mb-0">{children}</p>
      {onRetry ? (
        <Button variant="outline-secondary" size="sm" className="d-inline-flex align-items-center gap-2" onClick={onRetry}>
          <ArrowClockwise aria-hidden />
          Try again
        </Button>
      ) : null}
    </div>
  )
}

export function DashboardPage() {
  const [detailId, setDetailId] = useState<string | null>(null)
  const stats = useQuery({
    queryKey: ['stats', 'dashboard'],
    queryFn: async () => (await client.GET('/api/v1/stats/dashboard')).data,
    refetchInterval: 30_000,
  })
  const recentBackups = useQuery({
    queryKey: ['backups', 'recent'],
    queryFn: async () =>
      (
        await client.GET('/api/v1/backups', {
          params: { query: { pageSize: 8, sort: 'createdAt', order: 'desc' } },
        })
      ).data,
    refetchInterval: 15_000,
  })

  const totals = stats.data?.data?.totals
  const perDatabase = stats.data?.data?.databases ?? []
  const stale = stats.data?.data?.stale ?? []
  const activity = stats.data?.data?.activity ?? []
  const hasBackups = (totals?.backups ?? 0) > 0
  const hasStorage = (totals?.storageBytes ?? 0) > 0

  return (
    <>
      <PageHeader title="Dashboard" description="What your backups have been up to." />

      <div className="row row-cols-1 row-cols-sm-2 row-cols-lg-3 row-cols-xl-5 g-3">
        <StatCard
          title="Databases"
          value={totals?.databases}
          icon={Database}
          to="/databases"
          loading={stats.isPending}
        />
        <StatCard title="Hosts" value={totals?.hosts} icon={HddRack} to="/hosts" loading={stats.isPending} />
        <StatCard title="Backups" value={totals?.backups} icon={Archive} to="/backups" loading={stats.isPending} />
        <StatCard
          title="Storage used"
          value={totals ? formatBytes(totals.storageBytes) : undefined}
          icon={Hdd}
          to="/backups"
          loading={stats.isPending}
        />
        <StatCard
          title="Failed (7 days)"
          value={totals?.failedLast7Days}
          icon={ExclamationTriangle}
          to="/backups?state=FAILED"
          loading={stats.isPending}
        />
      </div>

      {stale.length ? <StaleBackupsCard stale={stale} /> : null}

      <div className="row g-3 mt-1">
        <div className="col-12 col-lg-8">
          <Card className="h-100">
            <Card.Header className="d-flex align-items-center justify-content-between bg-transparent">
              <span className="small fw-medium">Backup activity — last 14 days</span>
              <ActivityLegend />
            </Card.Header>
            <Card.Body>
              {stats.isPending ? (
                <Skeleton height="14rem" />
              ) : stats.isError ? (
                <ChartEmpty onRetry={() => void stats.refetch()}>Statistics could not be loaded.</ChartEmpty>
              ) : !hasBackups ? (
                <ChartEmpty>No backups yet.</ChartEmpty>
              ) : (
                <Suspense fallback={<Skeleton height="14rem" />}>
                  <ActivityChart activity={activity} />
                </Suspense>
              )}
            </Card.Body>
          </Card>
        </div>

        <div className="col-12 col-lg-4">
          <Card className="h-100">
            <Card.Header className="bg-transparent">
              <span className="small fw-medium">Storage by database</span>
            </Card.Header>
            <Card.Body>
              {stats.isPending ? (
                <Skeleton height="14rem" />
              ) : stats.isError ? (
                <ChartEmpty onRetry={() => void stats.refetch()}>Statistics could not be loaded.</ChartEmpty>
              ) : !hasStorage ? (
                <ChartEmpty>No stored backups yet.</ChartEmpty>
              ) : (
                <Suspense fallback={<Skeleton height="14rem" />}>
                  <StorageDonut databases={perDatabase} totalBytes={totals?.storageBytes ?? 0} />
                </Suspense>
              )}
            </Card.Body>
          </Card>
        </div>
      </div>

      {perDatabase.length > 0 && (
        <section className="mt-5" aria-label="Backups per database">
          <div className="d-flex align-items-center justify-content-between mb-3">
            <h2 className="fs-6 fw-medium mb-0">Backups per database</h2>
            <Link to="/databases" className="btn btn-link btn-sm">
              View all
            </Link>
          </div>
          <Card>
            <Table hover responsive className="mb-0 align-middle">
              <thead>
                <tr>
                  <th scope="col">Database</th>
                  <th scope="col" className="text-end">Backups</th>
                  <th scope="col" className="text-end">Storage</th>
                  <th scope="col" style={{ width: '33%' }}>Share</th>
                  <th scope="col">Last backup</th>
                </tr>
              </thead>
              <tbody>
                {perDatabase.map((db, index) => {
                  const share = hasStorage ? (db.storageBytes / (totals?.storageBytes ?? 1)) * 100 : 0
                  return (
                    <tr key={db.databaseId}>
                      <td className="fw-medium">
                        <span className="d-flex align-items-center gap-2">
                          <span
                            className="d-inline-block flex-shrink-0 rounded-1"
                            style={{ width: 10, height: 10, background: seriesColor(index) }}
                            aria-hidden
                          />
                          {db.databaseName}
                        </span>
                      </td>
                      <td className="text-end">{db.backupCount}</td>
                      <td className="text-end">{formatBytes(db.storageBytes)}</td>
                      <td>
                        <span className="d-flex align-items-center gap-2">
                          <span
                            className="progress w-100"
                            role="progressbar"
                            aria-label={`Storage share of ${db.databaseName}`}
                            aria-valuenow={Math.round(share)}
                            aria-valuemin={0}
                            aria-valuemax={100}
                            style={{ height: 6, maxWidth: '10rem' }}
                          >
                            <span className="progress-bar" style={{ width: `${share}%`, backgroundColor: seriesColor(index) }} />
                          </span>
                          <span className="flex-shrink-0 text-end small text-body-secondary" style={{ width: '2.5rem' }}>
                            {Math.round(share)}%
                          </span>
                        </span>
                      </td>
                      <td className="text-body-secondary">{formatDateTime(db.lastBackupAt)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </Table>
          </Card>
        </section>
      )}

      <section className="mt-5" aria-label="Recent backups">
        <div className="d-flex align-items-center justify-content-between mb-3">
          <h2 className="fs-6 fw-medium mb-0">Recent backups</h2>
          <Link to="/backups" className="btn btn-link btn-sm">
            View all
          </Link>
        </div>
        {recentBackups.isPending ? (
          <Skeleton height="12rem" />
        ) : recentBackups.isError ? (
          <ErrorState message="Backups could not be loaded." onRetry={() => recentBackups.refetch()} />
        ) : !recentBackups.data?.data?.length ? (
          <p className="border border-dashed rounded p-4 text-center small text-body-secondary mb-0">
            No backups yet. Add a database and trigger the first one from the Databases page.
          </p>
        ) : (
          <Card>
            <Table hover responsive className="mb-0 align-middle">
              <thead>
                <tr>
                  <th scope="col">Database</th>
                  <th scope="col">Created</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="text-end">Size</th>
                </tr>
              </thead>
              <tbody>
                {recentBackups.data.data.map((backup) => (
                  <tr key={backup.id} role="button" onClick={() => setDetailId(backup.id ?? null)}>
                    {/* the name is the real control; see BackupsPage */}
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
                    <td>
                      <BackupStateBadge state={backup.state ?? null} />
                    </td>
                    <td className="text-end">{backup.size ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </Card>
        )}
      </section>

      <BackupDetailDialog backupId={detailId} onOpenChange={(open) => !open && setDetailId(null)} />
    </>
  )
}
