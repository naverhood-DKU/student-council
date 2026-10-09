import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { useAuth } from './auth'
import { LoginPage } from './pages/LoginPage'
import { OrgSelectPage } from './pages/OrgSelectPage'
import { DashboardPage } from './pages/DashboardPage'
import { MeetingNewPage } from './pages/MeetingNewPage'
import { MeetingPage } from './pages/MeetingPage'
import { BoardPage } from './pages/BoardPage'
import { InboxPage } from './pages/InboxPage'
import { SentPage } from './pages/SentPage'
import { DeliveryStatusPage } from './pages/DeliveryStatusPage'

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/orgs" element={<RequireAuth><OrgSelectPage /></RequireAuth>} />
      <Route path="/orgs/:orgId" element={<RequireAuth><DashboardPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/meetings/new" element={<RequireAuth><MeetingNewPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/meetings/:meetingId" element={<RequireAuth><MeetingPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/board" element={<RequireAuth><BoardPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/inbox" element={<RequireAuth><InboxPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/sent" element={<RequireAuth><SentPage /></RequireAuth>} />
      <Route
        path="/orgs/:orgId/decisions/:decisionId/deliveries"
        element={<RequireAuth><DeliveryStatusPage /></RequireAuth>}
      />
      <Route path="*" element={<Navigate to="/orgs" replace />} />
    </Routes>
  )
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { state } = useAuth()
  if (state.status === 'loading') return <p className="page-message">불러오는 중…</p>
  if (state.status === 'signed-out') return <Navigate to="/login" replace />
  return children
}
