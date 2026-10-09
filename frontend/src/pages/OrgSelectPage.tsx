import { Link, Navigate } from 'react-router'
import type { MembershipView } from '../api'
import { ORG_TYPE_LABEL, positionLabel } from '../labels'
import { useAuth } from '../auth'
import { JoinPanel } from './JoinPanel'
import { TopBar } from './TopBar'

interface OrgChoice {
  orgId: number
  orgName: string
  orgType: MembershipView['orgType']
  roles: string[]
}

export function OrgSelectPage() {
  const { state } = useAuth()
  if (state.status !== 'signed-in') return null

  const choices = groupByOrg(state.me.memberships)
  const admin = state.me.serviceAdmin
  // 소속이 하나뿐이고 볼 가입 신청도 없으면 바로 들어간다. 소속 없는 관리자는 관리자 화면으로.
  if (!admin && choices.length === 1 && state.me.joinRequests.length === 0) return <Navigate to={`/orgs/${choices[0].orgId}`} replace />
  if (admin && choices.length === 0) return <Navigate to="/admin" replace />

  return (
    <>
      <TopBar />
      <main className="page">
        <h2>그룹 선택</h2>
        {admin && (
          <p><Link to="/admin" className="ghost">서비스 관리 화면으로</Link></p>
        )}
        {choices.length === 0 ? (
          <p className="empty">
            아직 소속된 그룹이 없습니다. 가입 신청을 회장이 승인하면 여기에 그룹이 나타납니다.
          </p>
        ) : (
          <ul className="card-list">
            {choices.map((choice) => (
              <li key={choice.orgId}>
                <Link to={`/orgs/${choice.orgId}`} className="card link-card">
                  <span className="chip">{ORG_TYPE_LABEL[choice.orgType]}</span>
                  <strong>{choice.orgName}</strong>
                  <span className="muted small">{choice.roles.join(' · ')}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        <JoinPanel />
      </main>
    </>
  )
}

function groupByOrg(memberships: MembershipView[]): OrgChoice[] {
  const byOrg = new Map<number, OrgChoice>()
  for (const m of memberships) {
    const choice = byOrg.get(m.orgId) ?? { orgId: m.orgId, orgName: m.orgName, orgType: m.orgType, roles: [] }
    const label = m.departmentName ? `${positionLabel(m)}(${m.departmentName})` : positionLabel(m)
    choice.roles.push(label)
    byOrg.set(m.orgId, choice)
  }
  return [...byOrg.values()]
}
