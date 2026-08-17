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
    <div className="border rounded bg-body px-3 py-2 shadow-sm small">
      <p className="mb-1 d-flex align-items-center gap-2 fw-medium">
        <span className="d-inline-block rounded-1" style={{ width: 8, height: 8, background: slice.color }} aria-hidden />
        {slice.name}
      </p>
      <p className="mb-0">
        {formatBytes(slice.bytes)} · {share}%
      </p>
    </div>
  )
}

/** Donut of storage share per database with the total in the center. */
export function StorageDonut({ databases, totalBytes }: { databases: DatabaseStats[]; totalBytes: number }) {
  const slices = storageSlices(databases)
  return (
    <div className="d-flex flex-column align-items-center gap-3">
      <div className="position-relative w-100" style={{ height: '12rem' }} role="img" aria-label="Share of backup storage per database">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={slices}
              dataKey="bytes"
              nameKey="name"
              innerRadius="65%"
              outerRadius="95%"
              paddingAngle={slices.length > 1 ? 2 : 0}
              stroke="var(--bs-body-bg)"
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
        <div className="position-absolute top-0 start-0 w-100 h-100 d-flex flex-column align-items-center justify-content-center pe-none">
          <span className="fs-5 fw-semibold">{formatBytes(totalBytes)}</span>
          <span className="small text-body-secondary">total</span>
        </div>
      </div>
      <ul className="list-unstyled w-100 mb-0 small">
        {slices.map((slice) => (
          <li key={slice.name} className="d-flex align-items-center gap-2 mb-1">
            <span className="d-inline-block flex-shrink-0 rounded-1" style={{ width: 10, height: 10, background: slice.color }} aria-hidden />
            <span className="text-truncate">{slice.name}</span>
            <span className="ms-auto text-body-secondary">{formatBytes(slice.bytes)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
