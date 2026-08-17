import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import Button from 'react-bootstrap/Button'
import Container from 'react-bootstrap/Container'
import Offcanvas from 'react-bootstrap/Offcanvas'
import {
  Archive,
  BoxArrowRight,
  Database,
  HddRack,
  Key,
  List,
  Lock,
  MoonStars,
  People,
  Speedometer2,
  Sun,
} from 'react-bootstrap-icons'

import { Logo } from '@/components/brand/Logo'
import { isAuthenticated, setSessionExpiredHandler, signOut, subscribe } from '@/lib/auth'
import { currentTheme, setTheme, subscribeTheme } from '@/lib/theme'

const navItems = [
  { to: '/', label: 'Dashboard', icon: Speedometer2, end: true },
  { to: '/backups', label: 'Backups', icon: Archive },
  { to: '/databases', label: 'Databases', icon: Database },
  { to: '/hosts', label: 'Hosts', icon: HddRack },
  { to: '/api-keys', label: 'API keys', icon: Key },
  { to: '/users', label: 'Users', icon: People },
]

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `nav-link d-flex align-items-center gap-2${isActive ? ' active' : ' link-body-emphasis'}`

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav aria-label="Main" className="nav nav-pills flex-column gap-1 flex-grow-1">
      {navItems.map(({ to, label, icon: Icon, end }) => (
        <NavLink key={to} to={to} end={end} onClick={onNavigate} className={navLinkClass}>
          <Icon aria-hidden />
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
      className="nav-link link-body-emphasis d-flex align-items-center gap-2 text-start"
    >
      {dark ? <Sun aria-hidden /> : <MoonStars aria-hidden />}
      {dark ? 'Light mode' : 'Dark mode'}
    </button>
  )
}

function AccountLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="nav nav-pills flex-column gap-1">
      <hr className="my-2" />
      <ThemeToggle />
      <NavLink to="/settings/password" onClick={onNavigate} className={navLinkClass}>
        <Lock aria-hidden />
        Change password
      </NavLink>
      <button
        type="button"
        onClick={() => void signOut()}
        className="nav-link link-body-emphasis d-flex align-items-center gap-2 text-start"
      >
        <BoxArrowRight aria-hidden />
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
    <p className="px-3 pt-2 mb-0 small text-body-secondary">
      Pneumatik {__APP_VERSION__} ·{' '}
      <a
        href="https://github.com/Klackwerk/Pneumatik-Database-Backups"
        target="_blank"
        rel="noreferrer"
        className="link-secondary"
      >
        AGPL-3.0 source
      </a>
    </p>
  )
}

function Brand() {
  return (
    <Link to="/" className="d-flex align-items-center gap-2 px-2 py-1 text-decoration-none text-body">
      <Logo className="flex-shrink-0" width={28} height={28} />
      <span className="fs-5 fw-semibold">Pneumatik</span>
    </Link>
  )
}

/**
 * App shell for all authenticated pages: fixed sidebar on desktop,
 * offcanvas menu on mobile. Redirects to /login when no token is present.
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

  const closeMenu = () => setMenuOpen(false)

  return (
    <div className="d-flex min-vh-100">
      {/* desktop sidebar */}
      <aside className="app-sidebar d-none d-md-flex flex-column gap-3 flex-shrink-0 border-end bg-body-tertiary p-3">
        <Brand />
        <NavLinks />
        <AccountLinks />
      </aside>

      <div className="d-flex flex-column flex-grow-1 min-w-0" style={{ minWidth: 0 }}>
        {/* mobile header */}
        <header className="d-md-none d-flex align-items-center gap-2 border-bottom bg-body-tertiary p-2">
          <Button variant="outline-secondary" aria-label="Open menu" onClick={() => setMenuOpen(true)}>
            <List aria-hidden />
          </Button>
          <Brand />
        </header>

        <Offcanvas show={menuOpen} onHide={closeMenu} placement="start" aria-label="Navigation">
          <Offcanvas.Header closeButton>
            <Brand />
          </Offcanvas.Header>
          <Offcanvas.Body className="d-flex flex-column gap-3">
            <NavLinks onNavigate={closeMenu} />
            <AccountLinks onNavigate={closeMenu} />
          </Offcanvas.Body>
        </Offcanvas>

        <main className="flex-grow-1 p-3 p-md-4">
          <Container fluid="xl" className="px-0" style={{ maxWidth: '72rem' }}>
            <Outlet />
          </Container>
        </main>
      </div>
    </div>
  )
}
