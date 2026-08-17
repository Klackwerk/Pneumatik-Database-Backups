import type { ReactNode } from 'react'

/** One header pattern for every page: title, optional subtitle, primary action on the right. */
export function PageHeader({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="d-flex flex-wrap align-items-start justify-content-between gap-3 mb-4">
      <div>
        <h1 className="h4 mb-0">{title}</h1>
        {description ? <p className="text-body-secondary small mt-1 mb-0">{description}</p> : null}
      </div>
      {action}
    </div>
  )
}
