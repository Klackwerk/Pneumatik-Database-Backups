const dateTime = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
})

/** "21 Jul 2026, 22:14" in the viewer's locale; em dash when absent. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date)
}

/** "1.4 GB" / "823 MB"; em dash for null, "0 B" for zero. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 'B'
  for (const next of units) {
    if (value < 1024) break
    value /= 1024
    unit = next
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${unit}`
}

/** "2m 14s" / "870ms" / "1h 3m"; em dash for null/negative. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`
}

export const triggerLabels: Record<string, string> = {
  TRIGGER_HOURLY: 'Hourly',
  TRIGGER_4HOURLY: 'Every 4 hours',
  TRIGGER_12HOURLY: 'Every 12 hours',
  TRIGGER_DAILY: 'Daily',
  TRIGGER_MANUAL: 'Manual only',
}

export const databaseTypeLabels: Record<string, string> = {
  MYSQL: 'MySQL / MariaDB',
  POSTGRESQL: 'PostgreSQL',
}

export const storageProviderLabels: Record<string, string> = {
  DIRECT: 'Local disk',
  S3: 'S3 object storage',
}

export const backupStateLabels: Record<string, string> = {
  CREATED: 'Queued',
  RUNNING: 'Running',
  FINISHED: 'Finished',
  FAILED: 'Failed',
}
