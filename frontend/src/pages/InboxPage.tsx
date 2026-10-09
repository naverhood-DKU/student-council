import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, ApiError, type DeliveryPatch, type DeliveryView, type OrgDetail } from '../api'
import { DELIVERY_STATUS_LABEL, formatDateTime, REQUEST_TYPE_LABEL } from '../labels'
import { canManageReceived, errorMessage, useLoad, useOrg } from '../useOrg'
import { TopBar } from './TopBar'

/**
 * 받은 요청(수신함). 모바일에서 한 손으로 수락·확인 요청·결과 제출을 할 수 있게 버튼을 크고 한 줄에 둔다.
 * 보낸 조직의 회의록은 볼 수 없고, 전달된 결정 요약과 요청 사항만 보인다.
 */
export function InboxPage() {
  const orgId = Number(useParams().orgId)
  const [org] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <Inbox org={org.data} />}
      </main>
    </>
  )
}

const FINISHED = new Set(['DONE', 'NOT_APPLICABLE', 'CANCELLED'])

function Inbox({ org }: { org: OrgDetail }) {
  const [items, reload] = useLoad<DeliveryView[]>(() => api.inbox(org.id), [org.id], '받은 요청을 불러오지 못했습니다.')

  if (items.status === 'loading') return <p className="page-message">불러오는 중…</p>
  if (items.status === 'error') return <p className="error">{items.message}</p>
  const open = items.data.filter((d) => !FINISHED.has(d.status))
  const finished = items.data.filter((d) => FINISHED.has(d.status))

  return (
    <>
      <h2>받은 요청 <span className="muted small">{open.length}건 진행 중</span></h2>
      <p className="muted small">상위 학생회가 보낸 결정과 요청입니다. 보낸 조직의 회의록은 보이지 않습니다.</p>
      {open.length === 0 && <p className="empty card">처리할 요청이 없습니다.</p>}
      <div className="stack">
        {open.map((d) => (
          <InboxCard key={`${d.id}-${d.version}`} delivery={d} org={org} onChanged={reload} />
        ))}
      </div>
      {finished.length > 0 && (
        <details className="cancelled">
          <summary>끝난 요청 {finished.length}건</summary>
          <div className="stack">
            {finished.map((d) => (
              <InboxCard key={`${d.id}-${d.version}`} delivery={d} org={org} onChanged={reload} />
            ))}
          </div>
        </details>
      )}
    </>
  )
}

type Panel = 'none' | 'clarify' | 'not-applicable' | 'submit'

function InboxCard({ delivery: d, org, onChanged }: { delivery: DeliveryView; org: OrgDetail; onChanged: () => void }) {
  const manager = canManageReceived(org)
  const worker = manager || (d.internalDepartmentId != null && org.myDepartmentIds.includes(d.internalDepartmentId))
  const [panel, setPanel] = useState<Panel>('none')
  const [text, setText] = useState('')
  const [link, setLink] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const latest = d.submissions[0]

  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') {
        setError('보낸 조직이 먼저 바꿨습니다(기한 변경 등). 최신 내용을 다시 불러왔습니다.')
        onChanged()
      } else {
        setError(errorMessage(e, fallback))
      }
      setBusy(false)
    }
  }

  const patch = (change: DeliveryPatch, fallback: string) => run(() => api.updateDelivery(d, change), fallback)

  return (
    <article className={`card inbox-card status-border-${d.status.toLowerCase()}`}>
      <div className="chips">
        <span className="chip">{REQUEST_TYPE_LABEL[d.requestType]}</span>
        <span className={`chip delivery-${d.status.toLowerCase()}`}>{DELIVERY_STATUS_LABEL[d.status]}</span>
      </div>
      <p className="muted small">{d.fromOrgName} · {formatDateTime(d.createdAt)}</p>
      <p className="summary"><strong>{d.decisionSummary}</strong></p>
      {d.message && <p className="request-message">{d.message}</p>}

      <p className="small">
        기한 <strong>{d.dueDate ?? '없음'}</strong>
        {d.dueChangedAt && (
          <span className="chip warn inline-chip">
            기한 변경됨: {d.previousDueDate ?? '없음'} → {d.dueDate ?? '없음'}
          </span>
        )}
      </p>

      {manager && !FINISHED.has(d.status) ? (
        <label className="compact inline-label">
          담당 부서
          <select
            value={d.internalDepartmentId ?? ''}
            disabled={busy}
            onChange={(e) => patch(
              { internalDepartmentId: e.target.value ? Number(e.target.value) : null },
              '담당 부서를 바꾸지 못했습니다.',
            )}
          >
            <option value="">미정</option>
            {org.departments.map((dep) => <option key={dep.id} value={dep.id}>{dep.name}</option>)}
          </select>
        </label>
      ) : (
        d.internalDepartmentName && <p className="muted small">담당 부서: {d.internalDepartmentName}</p>
      )}

      {d.status === 'CLARIFICATION_REQUESTED' && (
        <p className="notice small">확인 요청함: {d.clarificationReason} — 보낸 조직의 답변을 기다리는 중</p>
      )}
      {d.clarificationReply && d.status !== 'CLARIFICATION_REQUESTED' && (
        <p className="notice small">보낸 조직 답변: {d.clarificationReply}</p>
      )}
      {latest?.reviewStatus === 'REJECTED' && d.status === 'IN_PROGRESS' && (
        <p className="warn-card small">반려됨: {latest.reviewComment} — 고쳐서 다시 제출해 주세요.</p>
      )}
      {d.closingReason && FINISHED.has(d.status) && <p className="muted small">사유: {d.closingReason}</p>}
      {latest && (
        <p className="muted small">
          최근 제출: {latest.resultLink ? <a href={latest.resultLink} target="_blank" rel="noreferrer">링크</a> : null}
          {latest.description && ` ${latest.description}`} ({latest.submittedByName})
        </p>
      )}

      {error && <p className="error" role="alert">{error}</p>}

      {/* 상태별 행동 버튼 */}
      {d.status === 'PENDING' && manager && panel === 'none' && (
        <div className="big-actions">
          <button type="button" className="primary" disabled={busy}
                  onClick={() => patch({ status: 'ACCEPTED' }, '수락하지 못했습니다.')}>
            {d.requestType === 'INFORM' ? '확인했습니다' : '수락'}
          </button>
          <button type="button" className="ghost" disabled={busy} onClick={() => setPanel('clarify')}>확인 요청</button>
          <button type="button" className="ghost" disabled={busy} onClick={() => setPanel('not-applicable')}>해당 없음</button>
        </div>
      )}
      {(d.status === 'ACCEPTED' || d.status === 'IN_PROGRESS') && worker && panel === 'none' && (
        <div className="big-actions">
          {d.status === 'ACCEPTED' && (
            <button type="button" className="ghost" disabled={busy}
                    onClick={() => patch({ status: 'IN_PROGRESS' }, '시작하지 못했습니다.')}>
              시작
            </button>
          )}
          <button type="button" className="primary" disabled={busy} onClick={() => setPanel('submit')}>결과 제출</button>
        </div>
      )}
      {d.status === 'SUBMITTED' && <p className="muted small">보낸 조직이 결과를 검토하고 있습니다.</p>}
      {d.status === 'PENDING' && !manager && <p className="muted small">회장·조직 관리자가 수락하면 시작할 수 있습니다.</p>}

      {panel === 'clarify' && (
        <div className="panel">
          <label className="compact">
            무엇을 확인하고 싶나요? (필수)
            <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} maxLength={2000} autoFocus />
          </label>
          <div className="big-actions">
            <button type="button" className="primary" disabled={busy || !text.trim()}
                    onClick={() => patch({ status: 'CLARIFICATION_REQUESTED', reason: text }, '확인 요청을 보내지 못했습니다.')}>
              확인 요청 보내기
            </button>
            <button type="button" className="ghost" onClick={() => setPanel('none')}>닫기</button>
          </div>
        </div>
      )}
      {panel === 'not-applicable' && (
        <div className="panel">
          <label className="compact">
            해당 없는 이유 (선택)
            <textarea value={text} onChange={(e) => setText(e.target.value)} rows={2} maxLength={2000} autoFocus />
          </label>
          <div className="big-actions">
            <button type="button" className="primary" disabled={busy}
                    onClick={() => patch({ status: 'NOT_APPLICABLE', reason: text }, '처리하지 못했습니다.')}>
              해당 없음으로 처리
            </button>
            <button type="button" className="ghost" onClick={() => setPanel('none')}>닫기</button>
          </div>
        </div>
      )}
      {panel === 'submit' && (
        <div className="panel">
          <label className="compact">
            결과 링크 (구글 드라이브, 노션 등)
            <input type="url" inputMode="url" value={link} onChange={(e) => setLink(e.target.value)}
                   placeholder="https://" maxLength={1000} autoFocus />
          </label>
          <label className="compact">
            설명
            <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} maxLength={2000} />
          </label>
          <p className="muted small">링크나 설명 중 하나는 있어야 합니다. 낸 결과는 고칠 수 없고, 반려되면 새로 냅니다.</p>
          <div className="big-actions">
            <button type="button" className="primary" disabled={busy || (!link.trim() && !text.trim())}
                    onClick={() => run(() => api.submitResult(d.id, { resultLink: link, description: text }), '결과를 제출하지 못했습니다.')}>
              제출
            </button>
            <button type="button" className="ghost" onClick={() => setPanel('none')}>닫기</button>
          </div>
        </div>
      )}
    </article>
  )
}
