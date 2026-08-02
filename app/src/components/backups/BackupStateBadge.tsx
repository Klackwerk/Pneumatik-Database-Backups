import { Badge } from '@/components/ui/badge'
import { backupStateLabels } from '@/lib/format'

export function BackupStateBadge({ state }: { state: string | null }) {
  if (!state) return <span className="text-muted-foreground">—</span>
  const variant = state === 'FAILED' ? 'destructive' : state === 'FINISHED' ? 'secondary' : 'outline'
  return <Badge variant={variant}>{backupStateLabels[state] ?? state}</Badge>
}
