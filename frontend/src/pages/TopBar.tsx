import { Link } from 'react-router'
import { useAuth } from '../auth'

export function TopBar() {
  const { state, logout } = useAuth()
  const orgCount =
    state.status === 'signed-in' ? new Set(state.me.memberships.map((m) => m.orgId)).size : 0

  return (
    <header className="topbar">
      <Link to="/orgs" className="brand">결정 추적</Link>
      <div className="topbar-actions">
        {state.status === 'signed-in' && <span className="muted small">{state.me.name}</span>}
        {orgCount > 1 && <Link to="/orgs" className="ghost">조직 바꾸기</Link>}
        <button type="button" className="ghost" onClick={logout}>로그아웃</button>
      </div>
    </header>
  )
}
