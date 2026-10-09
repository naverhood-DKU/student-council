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
        {state.status === 'signed-in' && (
          <Link to="/account" className="muted small" title="내 계정·비밀번호">{state.me.name}</Link>
        )}
        {state.status === 'signed-in' && state.me.serviceAdmin && <Link to="/admin" className="ghost">서비스 관리</Link>}
        {orgCount > 1 && <Link to="/orgs" className="ghost">그룹 바꾸기</Link>}
        <button type="button" className="ghost" onClick={logout}>로그아웃</button>
      </div>
    </header>
  )
}
