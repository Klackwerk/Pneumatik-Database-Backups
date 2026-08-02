import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import type { components } from '@/api/schema'
import { FAILED_PATTERN_ID, failedSwatch } from '@/components/dashboard/series'

type ActivityDay = components['schemas']['ActivityDay']

const dayLabel = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })

function formatDay(date: string): string {
  return dayLabel.format(new Date(`${date}T00:00:00`))
}

function ActivityTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean
  payload?: { dataKey?: string | number; value?: number | string }[]
  label?: string
}) {
  if (!active || !payload?.length || !label) return null
  const finished = payload.find((p) => p.dataKey === 'finished')?.value ?? 0
  const failed = payload.find((p) => p.dataKey === 'failed')?.value ?? 0
  return (
    <div className="rounded-md border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
      <p className="mb-1 font-medium">{formatDay(label)}</p>
      <p className="flex items-center gap-1.5">
        <span className="size-2 rounded-[2px]" style={{ background: 'var(--chart-good)' }} aria-hidden />
        Finished: <span className="font-medium tabular-nums">{finished}</span>
      </p>
      <p className="flex items-center gap-1.5">
        <span className="size-2 rounded-[2px]" style={failedSwatch} aria-hidden />
        Failed: <span className="font-medium tabular-nums">{failed}</span>
      </p>
    </div>
  )
}

/** Stacked daily backup counts for the last 14 days: finished vs failed. */
export function ActivityChart({ activity }: { activity: ActivityDay[] }) {
  return (
    <div
      className="h-56"
      role="img"
      aria-label="Backups per day over the last 14 days, finished in solid bars versus failed in hatched bars"
    >
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={activity} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
          <defs>
            {/* the hatch that keeps "failed" legible without colour — see series.ts */}
            <pattern
              id={FAILED_PATTERN_ID}
              width={6}
              height={6}
              patternUnits="userSpaceOnUse"
              patternTransform="rotate(45)"
            >
              <rect width={6} height={6} fill="var(--chart-bad)" />
              <line x1={0} y1={0} x2={0} y2={6} stroke="var(--card)" strokeWidth={2.5} opacity={0.65} />
            </pattern>
          </defs>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="date"
            tickFormatter={formatDay}
            axisLine={false}
            tickLine={false}
            interval={1}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
          />
          <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }} />
          <Tooltip content={<ActivityTooltip />} cursor={{ fill: 'var(--muted)', opacity: 0.5 }} />
          <Bar dataKey="finished" name="Finished" stackId="day" fill="var(--chart-good)" stroke="var(--card)" strokeWidth={1} isAnimationActive={false} />
          <Bar
            dataKey="failed"
            name="Failed"
            stackId="day"
            fill={`url(#${FAILED_PATTERN_ID})`}
            stroke="var(--chart-bad)"
            strokeWidth={1}
            radius={[3, 3, 0, 0]}
            isAnimationActive={false}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
