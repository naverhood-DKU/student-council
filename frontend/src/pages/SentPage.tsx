import { Link, useParams } from 'react-router'
import { api, type SentDecision } from '../api'
import { tallyText } from '../labels'
import { useLoad } from '../useOrg'
import { TopBar } from './TopBar'

/** 보낸 요청: 우리 그룹이 다른 그룹에 요청한 결정 목록과 결정별 집계 */
export function SentPage() {
  const orgId = Number(useParams().orgId)
  const [sent] = useLoad<SentDecision[]>(() => api.sentDeliveries(orgId), [orgId], '보낸 요청을 불러오지 못했습니다.')

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        <h2>보낸 요청</h2>
        <p className="muted small">다른 그룹에 업무를 요청하려면 회의 검토 화면의 승인된 결정에서 "다른 그룹에 업무 요청"을 누릅니다(회장만).</p>
        {sent.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {sent.status === 'error' && <p className="error" role="alert">{sent.message}</p>}
        {sent.status === 'ready' && sent.data.length === 0 && <p className="empty card">아직 보낸 요청이 없습니다.</p>}
        {sent.status === 'ready' && sent.data.length > 0 && (
          <ul className="card-list">
            {sent.data.map((s) => (
              <li key={s.decisionId}>
                <Link to={`/orgs/${orgId}/decisions/${s.decisionId}/deliveries`} className="card link-card">
                  <span className="muted small">{s.meetingTitle} · {s.agenda}</span>
                  <strong>{s.summary}</strong>
                  <span className="tally small">{tallyText(s.tally)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </main>
    </>
  )
}
