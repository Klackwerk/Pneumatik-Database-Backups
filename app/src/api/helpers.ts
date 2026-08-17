/**
 * Error-envelope handling. The backend returns
 * {"error": {"code", "message", "fields": {field: [{code, message}]}}}
 * for every failure; validation failures (422) carry per-field errors.
 */

export type FieldErrors = Record<string, string>

export interface ApiFailure {
  message: string
  fields: FieldErrors
}

interface ErrorEnvelope {
  error?: {
    code?: string
    message?: string
    fields?: Record<string, { code?: string; message?: string }[]>
  }
}

/** Friendly texts for the machine-readable constraint codes. */
const codeMessages: Record<string, string> = {
  'nullable': 'Required',
  'blank': 'Required',
  'unique': 'Already in use',
  'uniqueEmail': 'Already in use',
  'notFound': 'Does not exist',
  'minSize.notmet': 'Too short',
  'min.notmet': 'Must be at least 1',
  'wrong': 'Wrong password',
  'notMatch': 'Passwords do not match',
  'atLeastOneLimit': 'Set a count or an age limit',
  'unknownRole': 'Unknown role',
  'email.invalid': 'Not a valid email address',
}

/** Turns an error envelope into a display message + field error map. */
export function parseFailure(body: unknown, fallback = 'Something went wrong. Try again.'): ApiFailure {
  const envelope = body as ErrorEnvelope | undefined
  const fields: FieldErrors = {}
  if (envelope?.error?.fields) {
    for (const [field, errors] of Object.entries(envelope.error.fields)) {
      const first = errors[0]
      if (first) {
        fields[field] = (first.code && codeMessages[first.code]) ?? first.message ?? 'Invalid'
      }
    }
  }
  return {
    message: envelope?.error?.message ?? fallback,
    fields,
  }
}
