import { Suspense, lazy, type ComponentType } from 'react'
import { BrowserRouter, Outlet, Route, Routes } from 'react-router-dom'

import { AuthenticatedLayout } from '@/components/layout/AuthenticatedLayout'
import { Skeleton } from '@/components/shared/Skeleton'
import { Toaster } from '@/components/shared/Toaster'
import { LoginPage } from '@/pages/LoginPage'

/**
 * Routes load on demand. The dashboard alone pulls in recharts, which is
 * larger than the rest of the application put together — bundling it into
 * the entry chunk made every visitor download a charting library before the
 * login form could render.
 */
const lazyPage = <T extends Record<string, unknown>, K extends keyof T>(load: () => Promise<T>, name: K) =>
  lazy(() => load().then((module) => ({ default: module[name] as ComponentType })))

const DashboardPage = lazyPage(() => import('@/pages/DashboardPage'), 'DashboardPage')
const HostsPage = lazyPage(() => import('@/pages/HostsPage'), 'HostsPage')
const DatabasesPage = lazyPage(() => import('@/pages/DatabasesPage'), 'DatabasesPage')
const BackupsPage = lazyPage(() => import('@/pages/BackupsPage'), 'BackupsPage')
const ApiKeysPage = lazyPage(() => import('@/pages/ApiKeysPage'), 'ApiKeysPage')
const UsersPage = lazyPage(() => import('@/pages/UsersPage'), 'UsersPage')
const PasswordSettingsPage = lazyPage(() => import('@/pages/PasswordSettingsPage'), 'PasswordSettingsPage')

/** Stand-in while a route chunk loads; mirrors the pages' own skeletons. */
function PageFallback() {
  return (
    <div className="vstack gap-3" role="status" aria-label="Loading page">
      <Skeleton height="2.25rem" width="13rem" />
      <Skeleton height="16rem" />
    </div>
  )
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* eager: the entry point for everyone who is not signed in yet */}
        <Route path="/login" element={<LoginPage />} />
        <Route element={<AuthenticatedLayout />}>
          <Route
            element={
              <Suspense fallback={<PageFallback />}>
                <Outlet />
              </Suspense>
            }
          >
            <Route path="/" element={<DashboardPage />} />
            <Route path="/hosts" element={<HostsPage />} />
            <Route path="/databases" element={<DatabasesPage />} />
            <Route path="/backups" element={<BackupsPage />} />
            <Route path="/api-keys" element={<ApiKeysPage />} />
            <Route path="/users" element={<UsersPage />} />
            <Route path="/settings/password" element={<PasswordSettingsPage />} />
          </Route>
        </Route>
      </Routes>
      <Toaster />
    </BrowserRouter>
  )
}

export default App
