import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { useAuth } from './auth'
import { LoginPage } from './pages/LoginPage'
import { OrgSelectPage } from './pages/OrgSelectPage'
import { DashboardPage } from './pages/DashboardPage'
import { MeetingNewPage } from './pages/MeetingNewPage'
import { MeetingPage } from './pages/MeetingPage'
import { BoardPage } from './pages/BoardPage'
import { DepartmentBoardPage } from './pages/DepartmentBoardPage'
import { SignupPage } from './pages/SignupPage'
import { InboxPage } from './pages/InboxPage'
import { SentPage } from './pages/SentPage'
import { DeliveryStatusPage } from './pages/DeliveryStatusPage'
import { MembersPage } from './pages/MembersPage'
import { AccountPage } from './pages/AccountPage'
import { AdminPage } from './pages/AdminPage'
import { ExpensePage, ExpenseReviewPage } from './pages/ExpensePage'

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/orgs" element={<RequireAuth><OrgSelectPage /></RequireAuth>} />
      <Route path="/orgs/:orgId" element={<RequireAuth><DashboardPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/meetings/new" element={<RequireAuth><MeetingNewPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/meetings/:meetingId" element={<RequireAuth><MeetingPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/board" element={<RequireAuth><BoardPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/board/:departmentKey" element={<RequireAuth><DepartmentBoardPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/inbox" element={<RequireAuth><InboxPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/sent" element={<RequireAuth><SentPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/members" element={<RequireAuth><MembersPage /></RequireAuth>} />
      <Route path="/orgs/:orgId/expense" element={<RequireAuth><ExpensePage /></RequireAuth>} />
      <Route path="/orgs/:orgId/expense/:reviewId" element={<RequireAuth><ExpenseReviewPage /></RequireAuth>} />
      <Route path="/account" element={<RequireAuth><AccountPage /></RequireAuth>} />
      <Route path="/admin" element={<RequireAuth><AdminPage /></RequireAuth>} />
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
