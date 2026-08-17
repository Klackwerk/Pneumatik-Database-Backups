/** Loading placeholder block using Bootstrap's placeholder animation. */
export function Skeleton({
  height,
  width = '100%',
  className = '',
}: {
  height: string
  width?: string
  className?: string
}) {
  return (
    <div className={`placeholder-glow ${className}`} aria-hidden="true">
      <span className="placeholder d-block rounded" style={{ height, width }} />
    </div>
  )
}
