import type { ReactNode } from 'react'
import Button from 'react-bootstrap/Button'
import Modal from 'react-bootstrap/Modal'

/**
 * One confirmation pattern for every destructive action. The confirm label
 * names the action ("Delete backup"), never a bare "OK".
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  onConfirm,
  pending = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: ReactNode
  confirmLabel: string
  onConfirm: () => void
  pending?: boolean
}) {
  const close = () => onOpenChange(false)
  return (
    <Modal show={open} onHide={close} centered backdrop={pending ? 'static' : true} aria-labelledby="confirm-dialog-title">
      <Modal.Header closeButton={!pending}>
        <Modal.Title id="confirm-dialog-title" className="fs-5">
          {title}
        </Modal.Title>
      </Modal.Header>
      <Modal.Body className="text-body-secondary">{description}</Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={close} disabled={pending}>
          Cancel
        </Button>
        <Button variant="danger" onClick={onConfirm} disabled={pending}>
          {pending ? 'Working…' : confirmLabel}
        </Button>
      </Modal.Footer>
    </Modal>
  )
}
