export interface ToastItem {
  id: number
  variant: 'success' | 'danger'
  title: string
  description?: string
}

let items: ToastItem[] = []
let nextId = 1
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

export function subscribeToasts(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function currentToasts(): ToastItem[] {
  return items
}

export function dismissToast(id: number) {
  items = items.filter((item) => item.id !== id)
  emit()
}

function push(variant: ToastItem['variant'], title: string, options?: { description?: string }) {
  items = [...items, { id: nextId++, variant, title, description: options?.description }]
  emit()
}

/** Imperative toast API for mutations: `toast.success('Host saved')`. */
export const toast = {
  success: (title: string, options?: { description?: string }) => push('success', title, options),
  error: (title: string, options?: { description?: string }) => push('danger', title, options),
}
