import { useState } from 'react'
import Button from 'react-bootstrap/Button'
import { ArrowClockwise, ExclamationTriangle } from 'react-bootstrap-icons'

/**
 * Failure state for a list that could not be loaded.
 *
 * "Reload the page to retry" throws away everything the page holds — filters,
 * a half-filled dialog, the scroll position — to recover from what is usually
 * one failed request. Refetching the query costs none of that.
 */
export function ErrorState({
  message,
  onRetry,
}: {
  message: string
  onRetry: () => Promise<unknown> | unknown
}) {
  const [retrying, setRetrying] = useState(false)

  async function retry() {
    setRetrying(true)
    try {
      await onRetry()
    } finally {
      setRetrying(false)
    }
  }

  return (
    <div
      role="alert"
      className="d-flex flex-column align-items-center justify-content-center gap-2 border border-dashed border-danger-subtle rounded py-5 text-center"
    >
      <ExclamationTriangle size={32} className="text-danger" aria-hidden />
      <p className="fw-medium mb-0">{message}</p>
      <p className="text-body-secondary small mb-0" style={{ maxWidth: '24rem' }}>
        The server did not answer, or answered with an error. This is usually temporary.
      </p>
      <Button variant="outline-secondary" className="mt-2 d-inline-flex align-items-center gap-2" onClick={() => void retry()} disabled={retrying}>
        <ArrowClockwise aria-hidden />
        {retrying ? 'Retrying…' : 'Try again'}
      </Button>
    </div>
  )
}
