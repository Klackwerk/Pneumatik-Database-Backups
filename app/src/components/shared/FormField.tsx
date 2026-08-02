import type { ReactNode } from 'react'

import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field'

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
    <Field data-invalid={error ? true : undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      {children}
      {description && !error ? <FieldDescription>{description}</FieldDescription> : null}
      {error ? <FieldError>{error}</FieldError> : null}
    </Field>
  )
}
