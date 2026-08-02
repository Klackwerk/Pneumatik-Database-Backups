import { failedSwatch } from '@/components/dashboard/series'

/**
 * Deliberately kept out of ActivityChart: the legend sits in the card header
 * and must render with the rest of the dashboard, while the chart itself is
 * loaded on demand because it pulls in recharts.
 */
export function ActivityLegend() {
  return (
    <div className="flex items-center gap-4 text-xs text-muted-foreground">
      <span className="flex items-center gap-1.5">
        <span className="size-2.5 rounded-[3px]" style={{ background: 'var(--chart-good)' }} aria-hidden />
        Finished
      </span>
      <span className="flex items-center gap-1.5">
        <span className="size-2.5 rounded-[3px] ring-1 ring-[var(--chart-bad)]" style={failedSwatch} aria-hidden />
        Failed
      </span>
    </div>
  )
}
