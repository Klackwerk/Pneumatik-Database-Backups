import { useQuery } from '@tanstack/react-query'
import Modal from 'react-bootstrap/Modal'

import { client } from '@/api/client'
import { Skeleton } from '@/components/shared/Skeleton'
import { BackupStateBadge } from '@/components/backups/BackupStateBadge'
import { formatBytes, formatDateTime, formatDuration, storageProviderLabels, triggerLabels } from '@/lib/format'

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="d-flex align-items-baseline justify-content-between gap-4 border-bottom py-2">
      <dt className="flex-shrink-0 fw-normal text-body-secondary">{label}</dt>
      <dd className="mb-0 text-end text-truncate fw-medium">{value}</dd>
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
    <Modal show={backupId !== null} onHide={() => onOpenChange(false)} centered scrollable aria-labelledby="backup-detail-title">
      <Modal.Header closeButton>
        <div>
          <Modal.Title id="backup-detail-title" className="fs-5 d-flex align-items-center gap-2">
            Backup {detail?.filename ?? (backupId !== null ? `#${backupId}` : '')}
            {detail?.state != null && <BackupStateBadge state={detail.state} />}
          </Modal.Title>
          <p className="mb-0 small text-body-secondary">
            {detail ? `${detail.databaseName} — ${formatDateTime(detail.createdAt)}` : 'Loading backup details…'}
          </p>
        </div>
      </Modal.Header>
      <Modal.Body>
        {backup.isPending ? (
          <Skeleton height="12rem" />
        ) : backup.isError || !detail ? (
          <p className="small text-danger mb-0">The backup details could not be loaded.</p>
        ) : (
          <div className="vstack gap-3 small">
            <dl className="mb-0">
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
              <p className="small text-body-secondary mb-0">
                This archive is encrypted with the data-encryption key. Decrypt it with{' '}
                <code>pneumatik-decrypt.py</code> before restoring.
              </p>
            ) : null}

            {(detail.output || failed) && (
              <section aria-label="Command output">
                <h3 className={`fs-6 small fw-medium text-uppercase mb-2 ${failed ? 'text-danger' : 'text-body-secondary'}`}>
                  {failed ? 'Failure log' : 'Command output'}
                </h3>
                {detail.output ? (
                  <pre
                    className="border rounded bg-body-tertiary p-3 font-monospace small mb-0"
                    style={{ maxHeight: '14rem', overflow: 'auto', whiteSpace: 'pre-wrap' }}
                  >
                    {detail.output}
                  </pre>
                ) : (
                  <p className="small text-body-secondary mb-0">
                    No output was captured for this run. Older backups predate output capture.
                  </p>
                )}
              </section>
            )}
          </div>
        )}
      </Modal.Body>
    </Modal>
  )
}
