import type { components } from '@/api/schema'

type DatabaseStats = components['schemas']['DatabaseStats']

/** Fixed categorical order (amber, blue, magenta, violet, aqua); never cycled. */
export const SERIES_COLORS = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
] as const

export const OTHER_COLOR = 'var(--chart-other)'

/**
 * Failed backups are hatched, not merely red.
 *
 * Green-vs-red is the one pairing red-green colour blindness collapses, which
 * is roughly one in twelve men — and the activity chart's whole job is to make
 * failures stand out. The diagonal hatch carries the same distinction without
 * colour, and survives greyscale printing besides.
 *
 * The chart draws it as an SVG pattern; this is the same hatch as a CSS
 * gradient, for the HTML swatches in the legend and tooltip.
 */
export const FAILED_PATTERN_ID = 'activity-failed-hatch'

export const failedSwatch = {
  background:
    'repeating-linear-gradient(45deg, var(--chart-bad) 0 3px, color-mix(in oklab, var(--chart-bad) 35%, var(--bs-body-bg)) 3px 6px)',
}

/** Identity color for the database at `index` in the storage-descending list. */
export function seriesColor(index: number): string {
  // a negative index would also be out of range, so ?? covers both ends
  return SERIES_COLORS[index] ?? OTHER_COLOR
}

export type Slice = { name: string; bytes: number; color: string }

/** Top databases by storage; everything past the palette folds into "Other". */
export function storageSlices(databases: DatabaseStats[]): Slice[] {
  const withStorage = databases.filter((db) => db.storageBytes > 0)
  const top = withStorage.slice(0, SERIES_COLORS.length)
  const rest = withStorage.slice(SERIES_COLORS.length)
  const slices: Slice[] = top.map((db, i) => ({ name: db.databaseName, bytes: db.storageBytes, color: seriesColor(i) }))
  if (rest.length > 0) {
    slices.push({ name: `Other (${rest.length})`, bytes: rest.reduce((sum, db) => sum + db.storageBytes, 0), color: OTHER_COLOR })
  }
  return slices
}
