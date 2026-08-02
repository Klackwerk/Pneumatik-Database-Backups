import { useState } from 'react'
import { RefreshCw, TriangleAlert } from 'lucide-react'

import { Button } from '@/components/ui/button'

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
      className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-destructive/50 py-16 text-center"
    >
      <TriangleAlert className="size-8 text-destructive" aria-hidden />
      <p className="font-medium">{message}</p>
      <p className="max-w-sm text-sm text-muted-foreground">
        The server did not answer, or answered with an error. This is usually temporary.
      </p>
      <Button variant="outline" className="mt-2" onClick={() => void retry()} disabled={retrying}>
        <RefreshCw className={retrying ? 'size-4 animate-spin' : 'size-4'} aria-hidden />
        {retrying ? 'Retrying…' : 'Try again'}
      </Button>
    </div>
  )
}
