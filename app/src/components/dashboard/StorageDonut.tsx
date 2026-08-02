import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'

import type { components } from '@/api/schema'
import { storageSlices, type Slice } from '@/components/dashboard/series'
import { formatBytes } from '@/lib/format'

type DatabaseStats = components['schemas']['DatabaseStats']

function DonutTooltip({
  active,
  payload,
  total,
}: {
  active?: boolean
  payload?: { payload?: Slice }[]
  total: number
}) {
  const slice = payload?.[0]?.payload
  if (!active || !slice) return null
  const share = total > 0 ? Math.round((slice.bytes / total) * 100) : 0
  return (
    <div className="rounded-md border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
      <p className="mb-0.5 flex items-center gap-1.5 font-medium">
        <span className="size-2 rounded-[2px]" style={{ background: slice.color }} aria-hidden />
        {slice.name}
      </p>
      <p className="tabular-nums">
        {formatBytes(slice.bytes)} · {share}%
      </p>
    </div>
  )
}

/** Donut of storage share per database with the total in the center. */
export function StorageDonut({ databases, totalBytes }: { databases: DatabaseStats[]; totalBytes: number }) {
  const slices = storageSlices(databases)
  return (
    <div className="flex flex-col items-center gap-3">
      <div className="relative h-48 w-full" role="img" aria-label="Share of backup storage per database">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={slices}
              dataKey="bytes"
              nameKey="name"
              innerRadius="65%"
              outerRadius="95%"
              paddingAngle={slices.length > 1 ? 2 : 0}
              stroke="var(--card)"
              strokeWidth={2}
              isAnimationActive={false}
            >
              {slices.map((slice) => (
                <Cell key={slice.name} fill={slice.color} />
              ))}
            </Pie>
            <Tooltip content={<DonutTooltip total={totalBytes} />} />
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-lg font-semibold tabular-nums">{formatBytes(totalBytes)}</span>
          <span className="text-xs text-muted-foreground">total</span>
        </div>
      </div>
      <ul className="w-full space-y-1 text-xs">
        {slices.map((slice) => (
          <li key={slice.name} className="flex items-center gap-1.5">
            <span className="size-2.5 shrink-0 rounded-[3px]" style={{ background: slice.color }} aria-hidden />
            <span className="truncate">{slice.name}</span>
            <span className="ml-auto tabular-nums text-muted-foreground">{formatBytes(slice.bytes)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
