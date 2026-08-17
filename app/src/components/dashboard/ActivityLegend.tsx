import { failedSwatch } from '@/components/dashboard/series'

/**
 * Deliberately kept out of ActivityChart: the legend sits in the card header
 * and must render with the rest of the dashboard, while the chart itself is
 * loaded on demand because it pulls in recharts.
 */
export function ActivityLegend() {
  return (
    <div className="d-flex align-items-center gap-3 small text-body-secondary">
      <span className="d-flex align-items-center gap-2">
        <span className="d-inline-block rounded-1" style={{ width: 10, height: 10, background: 'var(--chart-good)' }} aria-hidden />
        Finished
      </span>
      <span className="d-flex align-items-center gap-2">
        <span
          className="d-inline-block rounded-1"
          style={{ width: 10, height: 10, boxShadow: 'inset 0 0 0 1px var(--chart-bad)', ...failedSwatch }}
          aria-hidden
        />
        Failed
      </span>
    </div>
  )
}
