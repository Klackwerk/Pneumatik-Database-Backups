/**
 * Pneumatik mark: a database whose discs are pressed together and driven up
 * into a cloud. Ink follows `currentColor`, the pressure chevrons take the
 * brand amber, so the mark works on either theme without a second asset.
 */
export function Logo({ className, title }: { className?: string; title?: string }) {
  return (
    <svg
      viewBox="0 0 64 64"
      className={className}
      role={title ? 'img' : 'presentation'}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      <g fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
        <path d="M17 22 A7 7 0 0 1 17 10 A11 11 0 0 1 38 8 A8 8 0 0 1 45 22 Z" />
        <ellipse cx="32" cy="42" rx="12" ry="4" />
        <path d="M20 42 v12 A12 4 0 0 0 44 54 V42" />
        <path d="M20 47 A12 4 0 0 0 44 47" />
        <path d="M20 51 A12 4 0 0 0 44 51" />
      </g>
      <g
        fill="none"
        stroke="var(--primary)"
        strokeWidth="3"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M26 34 l6 -5 6 5" />
        <path d="M26 29 l6 -5 6 5" />
      </g>
    </svg>
  )
}
