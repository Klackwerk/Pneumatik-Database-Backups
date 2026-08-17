import { backupStateLabels } from '@/lib/format'

const stateVariants: Record<string, string> = {
  FAILED: 'danger',
  FINISHED: 'success',
  RUNNING: 'info',
  CREATED: 'secondary',
}

export function BackupStateBadge({ state }: { state: string | null }) {
  if (!state) return <span className="text-body-secondary">—</span>
  const variant = stateVariants[state] ?? 'secondary'
  return (
    <span className={`badge bg-${variant}-subtle text-${variant}-emphasis border border-${variant}-subtle`}>
      {backupStateLabels[state] ?? state}
    </span>
  )
}
