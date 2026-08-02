import { useQuery } from '@tanstack/react-query'

import { client } from '@/api/client'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { BackupStateBadge } from '@/components/backups/BackupStateBadge'
import { formatBytes, formatDateTime, formatDuration, storageProviderLabels, triggerLabels } from '@/lib/format'

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-border/60 py-1.5 last:border-b-0">
      <dt className="shrink-0 text-muted-foreground">{label}</dt>
      <dd className="truncate text-right font-medium">{value}</dd>
    </div>
  )
}

/**
 * Everything recorded about one backup run: timing, sizes, exit code and the
 * captured command output — the logs, when the run failed.
 */
export function BackupDetailDialog({
  backupId,
  onOpenChange,
}: {
  backupId: string | null
  onOpenChange: (open: boolean) => void
}) {
  const backup = useQuery({
    queryKey: ['backups', 'detail', backupId],
    queryFn: async () =>
      (await client.GET('/api/v1/backups/{id}', { params: { path: { id: backupId! } } })).data?.data,
    enabled: backupId !== null,
  })

  const detail = backup.data
  const failed = detail?.state === 'FAILED'
  const compression =
    detail?.rawSizeBytes && detail?.archivedSizeBytes && detail.rawSizeBytes > 0
      ? `${Math.round((1 - detail.archivedSizeBytes / detail.rawSizeBytes) * 100)}% smaller`
      : null

  return (
    <Dialog open={backupId !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            Backup {detail?.filename ?? (backupId !== null ? `#${backupId}` : '')}
            {detail?.state != null && <BackupStateBadge state={detail.state} />}
          </DialogTitle>
          <DialogDescription>
            {detail ? `${detail.databaseName} — ${formatDateTime(detail.createdAt)}` : 'Loading backup details…'}
          </DialogDescription>
        </DialogHeader>

        {backup.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : backup.isError || !detail ? (
          <p className="text-sm text-destructive">The backup details could not be loaded.</p>
        ) : (
          <div className="space-y-4 text-sm">
            <dl>
              <DetailRow label="Database" value={detail.databaseName ?? '—'} />
              <DetailRow
                label="Triggered by"
                value={detail.createdBy ?? (detail.trigger ? (triggerLabels[detail.trigger] ?? detail.trigger) : 'Schedule')}
              />
              <DetailRow label="Started" value={formatDateTime(detail.executedAt)} />
              <DetailRow label="Finished" value={formatDateTime(detail.finishedAt)} />
              <DetailRow label="Duration" value={formatDuration(detail.durationMs)} />
              <DetailRow
                label="Size on disk"
                value={detail.archivedSizeBytes != null ? formatBytes(detail.archivedSizeBytes) : '—'}
              />
              <DetailRow
                label="Uncompressed"
                value={
                  detail.rawSizeBytes != null
                    ? `${formatBytes(detail.rawSizeBytes)}${compression ? ` (${compression})` : ''}`
                    : (detail.size ?? '—')
                }
              />
              <DetailRow
                label="Storage"
                value={detail.storageProvider ? (storageProviderLabels[detail.storageProvider] ?? detail.storageProvider) : '—'}
              />
              <DetailRow
                label="Encryption"
                value={detail.encrypted ? 'AES-256-GCM' : 'None — plain zip'}
              />
              <DetailRow label="Exit code" value={detail.exitCode ?? '—'} />
            </dl>

            {detail.encrypted ? (
              <p className="text-xs text-muted-foreground">
                This archive is encrypted with the data-encryption key. Decrypt it with{' '}
                <code className="rounded bg-muted px-1 py-0.5">tools/pneumatik-decrypt.py</code> before restoring.
              </p>
            ) : null}

            {(detail.output || failed) && (
              <section aria-label="Command output">
                <h3 className={`mb-1.5 text-xs font-medium uppercase tracking-wide ${failed ? 'text-destructive' : 'text-muted-foreground'}`}>
                  {failed ? 'Failure log' : 'Command output'}
                </h3>
                {detail.output ? (
                  <pre className="max-h-56 overflow-auto rounded-md border bg-muted/50 p-3 font-mono text-xs whitespace-pre-wrap">
                    {detail.output}
                  </pre>
                ) : (
                  <p className="text-xs text-muted-foreground">
                    No output was captured for this run. Older backups predate output capture.
                  </p>
                )}
              </section>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
