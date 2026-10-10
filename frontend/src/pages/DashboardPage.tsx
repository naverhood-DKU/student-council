import { Link, useParams } from 'react-router'
import { api, type BoardTask, type DeliveryView, type Meeting, type OrgDetail, type PendingJoinRequest } from '../api'
import { useAuth } from '../auth'
import { formatDateTime, MEETING_STATUS_LABEL, ORG_TYPE_LABEL, ROLE_LABEL } from '../labels'
import { isOwner, useLoad, useOrg } from '../useOrg'
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
  const { state } = useAuth()
  const myId = state.status === 'signed-in' ? state.me.id : null
  const [inbox] = useLoad<DeliveryView[]>(() => api.inbox(org.id), [org.id], '')
  const [board] = useLoad<BoardTask[]>(() => api.board(org.id), [org.id], '')
  const owner = isOwner(org)
  const [joins] = useLoad<PendingJoinRequest[]>(
    () => (owner ? api.joinRequests(org.id) : Promise.resolve([])),
    [org.id, owner],
    '',
  )
  const pendingJoins = joins.status === 'ready' ? joins.data.length : 0
  // 나에게 맡겨진, 아직 안 끝낸 체크리스트(끝난·취소된 업무 것은 뺀다)
  const myItems = board.status === 'ready'
    ? board.data
        .filter((t) => t.status !== 'DONE' && t.status !== 'CANCELLED')
        .flatMap((t) => t.items.filter((i) => !i.done && i.assigneeUserId === myId).map((i) => ({ item: i, task: t })))
    : []
  const openInbox = inbox.status === 'ready'
    ? inbox.data.filter((d) => !['DONE', 'NOT_APPLICABLE', 'CANCELLED'].includes(d.status)).length
    : 0
  const myDepartments = org.departments.filter((d) => org.myDepartmentIds.includes(d.id)).map((d) => d.name)

  return (
    <>
      <section className="org-header">
        <span className="chip">{ORG_TYPE_LABEL[org.type]} · {org.term} 임기</span>
        <h2>{org.name}</h2>
        {org.adminView ? (
          <p className="notice small">서비스 관리자 열람 중입니다. 읽기만 할 수 있습니다.</p>
        ) : (
          <p className="muted small">
            내 직책: {org.myPositionName ?? (org.myRole ? ROLE_LABEL[org.myRole] : '-')}
            {myDepartments.length > 0 && ` · ${myDepartments.join(', ')}`}
          </p>
        )}
      </section>

      <div className="actions-row">
        {owner && (
          <Link to={`/orgs/${org.id}/meetings/new`} className="button primary-link">회의록 올리기</Link>
        )}
        <Link to={`/orgs/${org.id}/board`} className="ghost">부서 업무보드</Link>
        <Link to={`/orgs/${org.id}/inbox`} className="ghost">
          받은 요청{openInbox > 0 && <span className="count-badge">{openInbox}</span>}
        </Link>
        <Link to={`/orgs/${org.id}/sent`} className="ghost">보낸 요청</Link>
        <Link to={`/orgs/${org.id}/expense`} className="ghost">지출 절차 확인</Link>
        <Link to={`/orgs/${org.id}/rules`} className="ghost">규정</Link>
        <Link to={`/orgs/${org.id}/members`} className="ghost">
          구성원·부서·직책{pendingJoins > 0 && <span className="count-badge" title="가입 신청">{pendingJoins}</span>}
        </Link>
      </div>

      {myItems.length > 0 && (
        <section className="card">
          <h3>나에게 맡겨진 일 <span className="muted small">{myItems.length}</span></h3>
          <ul className="row-list">
            {myItems.map(({ item, task }) => (
              <li key={item.id}>
                <Link to={`/orgs/${org.id}/board/${task.departmentId ?? 'none'}`} className="row-link">
                  <span>
                    <strong>{item.title}</strong>
                    <span className="muted small block">{task.departmentName} · {task.title} · 기한 {task.dueDate ?? '미정'}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

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
