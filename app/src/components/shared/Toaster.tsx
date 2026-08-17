import { useSyncExternalStore } from 'react'
import Toast from 'react-bootstrap/Toast'
import ToastContainer from 'react-bootstrap/ToastContainer'
import { CheckCircleFill, ExclamationTriangleFill } from 'react-bootstrap-icons'

import { currentToasts, dismissToast, subscribeToasts } from '@/lib/toast'

/** Renders the queued toasts; mount once at the application root. */
export function Toaster() {
  const current = useSyncExternalStore(subscribeToasts, currentToasts)
  return (
    <ToastContainer position="bottom-end" className="p-3 position-fixed" style={{ zIndex: 1090 }}>
      {current.map((item) => (
        <Toast key={item.id} onClose={() => dismissToast(item.id)} delay={6000} autohide role="status">
          <Toast.Body className="d-flex align-items-start gap-2">
            {item.variant === 'success' ? (
              <CheckCircleFill className="text-success mt-1 flex-shrink-0" aria-hidden />
            ) : (
              <ExclamationTriangleFill className="text-danger mt-1 flex-shrink-0" aria-hidden />
            )}
            <div className="me-auto">
              <div className="fw-semibold">{item.title}</div>
              {item.description ? <div className="text-body-secondary small">{item.description}</div> : null}
            </div>
            <button
              type="button"
              className="btn-close flex-shrink-0"
              aria-label="Dismiss notification"
              onClick={() => dismissToast(item.id)}
            />
          </Toast.Body>
        </Toast>
      ))}
    </ToastContainer>
  )
}
