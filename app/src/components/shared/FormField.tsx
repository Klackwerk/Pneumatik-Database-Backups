import type { ReactNode } from 'react'
import Form from 'react-bootstrap/Form'

/**
 * One field pattern for every form: label, control, hint, error — errors
 * from the API's 422 responses land here, next to the input they concern.
 */
export function FormField({
  id,
  label,
  error,
  description,
  children,
}: {
  id: string
  label: string
  error?: string
  description?: string
  children: ReactNode
}) {
  return (
    <Form.Group>
      <Form.Label htmlFor={id}>{label}</Form.Label>
      {children}
      {description && !error ? <Form.Text className="d-block">{description}</Form.Text> : null}
      {error ? <div className="invalid-feedback d-block">{error}</div> : null}
    </Form.Group>
  )
}
