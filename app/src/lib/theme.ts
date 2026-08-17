const STORAGE_KEY = 'pneumatik.theme'

export type Theme = 'light' | 'dark'

const darkScheme = window.matchMedia('(prefers-color-scheme: dark)')
const listeners = new Set<() => void>()

export function storedTheme(): Theme | null {
  const value = localStorage.getItem(STORAGE_KEY)
  return value === 'light' || value === 'dark' ? value : null
}

/** The active theme: the user's explicit choice, else the OS scheme. */
export function currentTheme(): Theme {
  return storedTheme() ?? (darkScheme.matches ? 'dark' : 'light')
}

function apply() {
  document.documentElement.setAttribute('data-bs-theme', currentTheme())
}

export function setTheme(theme: Theme) {
  localStorage.setItem(STORAGE_KEY, theme)
  apply()
  listeners.forEach((listener) => listener())
}

export function subscribeTheme(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Call once at startup: applies the theme and follows OS changes while no explicit choice is stored. */
export function initTheme() {
  apply()
  darkScheme.addEventListener('change', () => {
    apply()
    listeners.forEach((listener) => listener())
  })
}
