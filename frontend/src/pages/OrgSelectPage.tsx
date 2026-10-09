import { Link, Navigate } from 'react-router'
import type { MembershipView } from '../api'
import { ORG_TYPE_LABEL, ROLE_LABEL } from '../labels'
import { useAuth } from '../auth'
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
  // 소속이 하나뿐이면 고를 필요 없이 바로 들어간다.
  if (choices.length === 1) return <Navigate to={`/orgs/${choices[0].orgId}`} replace />

  return (
    <>
      <TopBar />
      <main className="page">
        <h2>조직 선택</h2>
        {choices.length === 0 ? (
          <p className="empty">활성 소속이 없습니다. 학생회 관리자에게 초대를 요청하세요.</p>
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
      </main>
    </>
  )
}

function groupByOrg(memberships: MembershipView[]): OrgChoice[] {
  const byOrg = new Map<number, OrgChoice>()
  for (const m of memberships) {
    const choice = byOrg.get(m.orgId) ?? { orgId: m.orgId, orgName: m.orgName, orgType: m.orgType, roles: [] }
    const label = m.departmentName ? `${ROLE_LABEL[m.role]}(${m.departmentName})` : ROLE_LABEL[m.role]
    choice.roles.push(label)
    byOrg.set(m.orgId, choice)
  }
  return [...byOrg.values()]
}
