import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import {
  Archive,
  Database,
  Gauge,
  KeyRound,
  LockKeyhole,
  LogOut,
  Menu,
  Moon,
  Server,
  Sun,
  Users,
} from 'lucide-react'

import { Logo } from '@/components/brand/Logo'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { isAuthenticated, setSessionExpiredHandler, signOut, subscribe } from '@/lib/auth'
import { currentTheme, setTheme, subscribeTheme } from '@/lib/theme'
import { cn } from '@/lib/utils'

const navItems = [
  { to: '/', label: 'Dashboard', icon: Gauge, end: true },
  { to: '/backups', label: 'Backups', icon: Archive },
  { to: '/databases', label: 'Databases', icon: Database },
  { to: '/hosts', label: 'Hosts', icon: Server },
  { to: '/api-keys', label: 'API keys', icon: KeyRound },
  { to: '/users', label: 'Users', icon: Users },
]

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav aria-label="Main" className="flex flex-1 flex-col gap-1">
      {navItems.map(({ to, label, icon: Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
              'focus-visible:outline-2 focus-visible:outline-ring',
              isActive
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-accent hover:text-foreground',
            )
          }
        >
          <Icon className="size-4" aria-hidden />
          {label}
        </NavLink>
      ))}
    </nav>
  )
}

function ThemeToggle() {
  const theme = useSyncExternalStore(subscribeTheme, currentTheme)
  const dark = theme === 'dark'
  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark}
      aria-label="Dark mode"
      onClick={() => setTheme(dark ? 'light' : 'dark')}
      className="flex items-center gap-3 rounded-md px-3 py-2 text-left text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
    >
      {dark ? <Sun className="size-4" aria-hidden /> : <Moon className="size-4" aria-hidden />}
      {dark ? 'Light mode' : 'Dark mode'}
    </button>
  )
}

function AccountLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="flex flex-col gap-1">
      <Separator className="my-2" />
      <ThemeToggle />
      <NavLink
        to="/settings/password"
        onClick={onNavigate}
        className={({ isActive }) =>
          cn(
            'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
            isActive
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:bg-accent hover:text-foreground',
          )
        }
      >
        <LockKeyhole className="size-4" aria-hidden />
        Change password
      </NavLink>
      <button
        type="button"
        onClick={() => void signOut()}
        className="flex items-center gap-3 rounded-md px-3 py-2 text-left text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
      >
        <LogOut className="size-4" aria-hidden />
        Sign out
      </button>
      <SourceNotice />
    </div>
  )
}

/**
 * AGPL section 13: users interacting with the application over a network have
 * to be told where the source is, so the offer lives in the app rather than
 * only in the repository.
 */
function SourceNotice() {
  return (
    <p className="px-3 pt-2 text-xs text-muted-foreground">
      Pneumatik {__APP_VERSION__} ·{' '}
      <a
        href="https://github.com/Klackwerk/Pneumatik-Database-Backups"
        target="_blank"
        rel="noreferrer"
        className="underline underline-offset-2 hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
      >
        AGPL-3.0 source
      </a>
    </p>
  )
}

function Brand() {
  return (
    <Link to="/" className="flex items-center gap-2 px-3 py-1 focus-visible:outline-2 focus-visible:outline-ring">
      <Logo className="size-7 shrink-0 text-foreground" />
      <span className="text-base font-semibold tracking-tight">Pneumatik</span>
    </Link>
  )
}

/**
 * App shell for all authenticated pages: fixed sidebar on desktop,
 * sheet menu on mobile. Redirects to /login when no token is present.
 */
export function AuthenticatedLayout() {
  const authenticated = useSyncExternalStore(subscribe, isAuthenticated)
  const [menuOpen, setMenuOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()

  // the API client lives outside React; give it a router navigation so an
  // expired session keeps the SPA (and the route it should return to)
  useEffect(() => {
    setSessionExpiredHandler((from) => navigate('/login', { replace: true, state: { from } }))
    return () => setSessionExpiredHandler(null)
  }, [navigate])

  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }

  return (
    <div className="flex min-h-svh">
      {/* desktop sidebar */}
      <aside className="sticky top-0 hidden h-svh w-60 shrink-0 flex-col gap-4 border-r bg-sidebar p-3 md:flex">
        <Brand />
        <NavLinks />
        <AccountLinks />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* mobile header */}
        <header className="flex items-center gap-2 border-b bg-sidebar p-2 md:hidden">
          <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" aria-label="Open menu">
                <Menu className="size-5" />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" className="flex w-64 flex-col gap-4 p-3">
              <SheetTitle className="sr-only">Navigation</SheetTitle>
              <Brand />
              <NavLinks onNavigate={() => setMenuOpen(false)} />
              <AccountLinks onNavigate={() => setMenuOpen(false)} />
            </SheetContent>
          </Sheet>
          <Brand />
        </header>

        <main className="mx-auto w-full max-w-6xl flex-1 p-4 md:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
