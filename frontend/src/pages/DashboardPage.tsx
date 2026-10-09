import { Link, useParams } from 'react-router'
import { api, type ChildOrg, type DeliveryView, type Meeting, type OrgDetail } from '../api'
import { formatDateTime, MEETING_STATUS_LABEL, ORG_TYPE_LABEL, ROLE_LABEL } from '../labels'
import { canUpload, useLoad, useOrg } from '../useOrg'
import { TopBar } from './TopBar'

export function DashboardPage() {
  const orgId = Number(useParams().orgId)
  const [org] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page">
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <Dashboard org={org.data} />}
      </main>
    </>
  )
}

function Dashboard({ org }: { org: OrgDetail }) {
  const [meetings] = useLoad<Meeting[]>(() => api.meetings(org.id), [org.id], '회의 목록을 불러오지 못했습니다.')
  const [links] = useLoad<{ inbox: DeliveryView[]; children: ChildOrg[] }>(
    async () => ({ inbox: await api.inbox(org.id), children: await api.children(org.id) }),
    [org.id],
    '',
  )
  const openInbox = links.status === 'ready'
    ? links.data.inbox.filter((d) => !['DONE', 'NOT_APPLICABLE', 'CANCELLED'].includes(d.status)).length
    : 0
  const hasChildren = links.status === 'ready' && links.data.children.length > 0

  return (
    <>
      <section className="org-header">
        <span className="chip">{ORG_TYPE_LABEL[org.type]} · {org.term} 임기</span>
        <h2>{org.name}</h2>
        <p className="muted small">내 역할: {org.myRoles.map((r) => ROLE_LABEL[r]).join(', ')}</p>
      </section>

      <div className="actions-row">
        {canUpload(org) && (
          <Link to={`/orgs/${org.id}/meetings/new`} className="button primary-link">회의록 올리기</Link>
        )}
        <Link to={`/orgs/${org.id}/board`} className="ghost">부서 업무보드</Link>
        <Link to={`/orgs/${org.id}/inbox`} className="ghost">
          받은 요청{openInbox > 0 && <span className="count-badge">{openInbox}</span>}
        </Link>
        {hasChildren && <Link to={`/orgs/${org.id}/sent`} className="ghost">보낸 요청</Link>}
      </div>

      <section className="card">
        <h3>회의</h3>
        {meetings.status === 'loading' && <p className="muted">불러오는 중…</p>}
        {meetings.status === 'error' && <p className="error">{meetings.message}</p>}
        {meetings.status === 'ready' && meetings.data.length === 0 && (
          <p className="empty">아직 등록된 회의가 없습니다.</p>
        )}
        {meetings.status === 'ready' && meetings.data.length > 0 && (
          <ul className="row-list">
            {meetings.data.map((m) => (
              <li key={m.id}>
                <Link to={`/orgs/${org.id}/meetings/${m.id}`} className="row-link">
                  <span>
                    <strong>{m.title}</strong>
                    <span className="muted small block">{formatDateTime(m.heldAt)}</span>
                  </span>
                  <span className={`chip status-${m.status.toLowerCase()}`}>{MEETING_STATUS_LABEL[m.status]}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}
