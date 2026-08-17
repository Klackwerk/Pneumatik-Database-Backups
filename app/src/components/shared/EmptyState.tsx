import type { ComponentType, ReactNode } from 'react'

/** Empty states give direction: what this list is and what to do next. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: ComponentType<{ size?: number; className?: string; 'aria-hidden'?: boolean }>
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="d-flex flex-column align-items-center justify-content-center gap-2 border border-dashed rounded py-5 text-center">
      <Icon size={32} className="text-body-secondary" aria-hidden />
      <p className="fw-medium mb-0">{title}</p>
      <p className="text-body-secondary small mb-0" style={{ maxWidth: '24rem' }}>
        {description}
      </p>
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
