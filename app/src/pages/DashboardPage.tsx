import { Suspense, lazy, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Archive, Database, HardDrive, RefreshCw, Server, TriangleAlert } from 'lucide-react'
import { Link } from 'react-router-dom'

import { client } from '@/api/client'
import type { components } from '@/api/schema'
import { BackupDetailDialog } from '@/components/backups/BackupDetailDialog'
import { BackupStateBadge } from '@/components/backups/BackupStateBadge'
import { ActivityLegend } from '@/components/dashboard/ActivityLegend'
import { seriesColor } from '@/components/dashboard/series'
import { ErrorState } from '@/components/shared/ErrorState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
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
    <Card className="mt-6 border-destructive/50">
      <CardHeader className="flex flex-row items-center gap-2 pb-3">
        <TriangleAlert className="size-4 text-destructive" aria-hidden />
        <CardTitle className="text-sm font-medium">
          {stale.length === 1
            ? '1 database has no recent successful backup'
            : `${stale.length} databases have no recent successful backup`}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="flex flex-col gap-2">
          {stale.map((database) => (
            <li key={database.databaseId} className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
              <Link
                to={`/backups?databaseId=${database.databaseId}`}
                className="font-medium hover:underline focus-visible:outline-2 focus-visible:outline-ring"
              >
                {database.databaseName}
              </Link>
              <span className="text-muted-foreground">
                {database.lastSuccessfulBackupAt
                  ? `last succeeded ${formatStaleAge(database.staleSinceDays)} — ${formatDateTime(database.lastSuccessfulBackupAt)}`
                  : 'never backed up successfully'}
              </span>
            </li>
          ))}
        </ul>
      </CardContent>
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
    <Link to={to} className="rounded-xl focus-visible:outline-2 focus-visible:outline-ring">
      <Card className="h-full transition-colors hover:bg-accent/50">
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
          <Icon className="size-4 text-muted-foreground" aria-hidden />
        </CardHeader>
        <CardContent>
          {loading ? <Skeleton className="h-8 w-14" /> : <p className="text-2xl font-semibold">{value ?? '—'}</p>}
        </CardContent>
      </Card>
    </Link>
  )
}

function ChartEmpty({ children, onRetry }: { children: string; onRetry?: () => void }) {
  return (
    <div className="flex h-48 flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
      <p>{children}</p>
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RefreshCw className="size-4" aria-hidden />
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

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
        <StatCard
          title="Databases"
          value={totals?.databases}
          icon={Database}
          to="/databases"
          loading={stats.isPending}
        />
        <StatCard title="Hosts" value={totals?.hosts} icon={Server} to="/hosts" loading={stats.isPending} />
        <StatCard title="Backups" value={totals?.backups} icon={Archive} to="/backups" loading={stats.isPending} />
        <StatCard
          title="Storage used"
          value={totals ? formatBytes(totals.storageBytes) : undefined}
          icon={HardDrive}
          to="/backups"
          loading={stats.isPending}
        />
        <StatCard
          title="Failed (7 days)"
          value={totals?.failedLast7Days}
          icon={TriangleAlert}
          to="/backups?state=FAILED"
          loading={stats.isPending}
        />
      </div>

      {stale.length ? <StaleBackupsCard stale={stale} /> : null}

      <div className="mt-6 grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle className="text-sm font-medium">Backup activity — last 14 days</CardTitle>
            <ActivityLegend />
          </CardHeader>
          <CardContent>
            {stats.isPending ? (
              <Skeleton className="h-56 w-full" />
            ) : stats.isError ? (
              <ChartEmpty onRetry={() => void stats.refetch()}>Statistics could not be loaded.</ChartEmpty>
            ) : !hasBackups ? (
              <ChartEmpty>No backups yet.</ChartEmpty>
            ) : (
              <Suspense fallback={<Skeleton className="h-56 w-full" />}>
                <ActivityChart activity={activity} />
              </Suspense>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium">Storage by database</CardTitle>
          </CardHeader>
          <CardContent>
            {stats.isPending ? (
              <Skeleton className="h-56 w-full" />
            ) : stats.isError ? (
              <ChartEmpty onRetry={() => void stats.refetch()}>Statistics could not be loaded.</ChartEmpty>
            ) : !hasStorage ? (
              <ChartEmpty>No stored backups yet.</ChartEmpty>
            ) : (
              <Suspense fallback={<Skeleton className="h-56 w-full" />}>
                <StorageDonut databases={perDatabase} totalBytes={totals?.storageBytes ?? 0} />
              </Suspense>
            )}
          </CardContent>
        </Card>
      </div>

      {perDatabase.length > 0 && (
        <section className="mt-8" aria-label="Backups per database">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="font-medium">Backups per database</h2>
            <Button variant="ghost" size="sm" asChild>
              <Link to="/databases">View all</Link>
            </Button>
          </div>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Database</TableHead>
                  <TableHead className="text-right">Backups</TableHead>
                  <TableHead className="text-right">Storage</TableHead>
                  <TableHead className="w-1/3">Share</TableHead>
                  <TableHead>Last backup</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {perDatabase.map((db, index) => {
                  const share = hasStorage ? (db.storageBytes / (totals?.storageBytes ?? 1)) * 100 : 0
                  return (
                    <TableRow key={db.databaseId}>
                      <TableCell className="font-medium">
                        <span className="flex items-center gap-2">
                          <span
                            className="size-2.5 shrink-0 rounded-[3px]"
                            style={{ background: seriesColor(index) }}
                            aria-hidden
                          />
                          {db.databaseName}
                        </span>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{db.backupCount}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatBytes(db.storageBytes)}</TableCell>
                      <TableCell>
                        <span className="flex items-center gap-2">
                          <span className="h-1.5 w-full max-w-40 overflow-hidden rounded-full bg-muted">
                            <span
                              className="block h-full rounded-full"
                              style={{ width: `${share}%`, background: seriesColor(index) }}
                            />
                          </span>
                          <span className="w-10 shrink-0 text-right text-xs tabular-nums text-muted-foreground">
                            {Math.round(share)}%
                          </span>
                        </span>
                      </TableCell>
                      <TableCell className="text-muted-foreground">{formatDateTime(db.lastBackupAt)}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        </section>
      )}

      <section className="mt-8" aria-label="Recent backups">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-medium">Recent backups</h2>
          <Button variant="ghost" size="sm" asChild>
            <Link to="/backups">View all</Link>
          </Button>
        </div>
        {recentBackups.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : recentBackups.isError ? (
          <ErrorState message="Backups could not be loaded." onRetry={() => recentBackups.refetch()} />
        ) : !recentBackups.data?.data?.length ? (
          <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
            No backups yet. Add a database and trigger the first one from the Databases page.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Database</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Size</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {recentBackups.data.data.map((backup) => (
                  <TableRow key={backup.id} className="cursor-pointer" onClick={() => setDetailId(backup.id ?? null)}>
                    {/* the name is the real control; see BackupsPage */}
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
                    <TableCell>
                      <BackupStateBadge state={backup.state ?? null} />
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{backup.size ?? '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </section>

      <BackupDetailDialog backupId={detailId} onOpenChange={(open) => !open && setDetailId(null)} />
    </>
  )
}
