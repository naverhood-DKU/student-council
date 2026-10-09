import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  api,
  ApiError,
  type GroupOption,
  type DecisionDeliveries,
  type DeliveryPatch,
  type DeliveryView,
  type OrgDetail,
  type RequestType,
} from '../api'
import { DELIVERY_STATUS_LABEL, formatDateTime, REQUEST_TYPE_LABEL, tallyText } from '../labels'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { TopBar } from './TopBar'

/**
 * 보낸 그룹의 요청 현황: 결정 하나로 다른 그룹들에 업무를 요청하고, 대상별 상태를 보고, 결과를 승인·반려한다.
 * 집계 분모(전체)는 해당 없음·취소를 뺀 유효 대상 수다.
 */
export function DeliveryStatusPage() {
  const params = useParams()
  const orgId = Number(params.orgId)
  const decisionId = Number(params.decisionId)
  const [org] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page">
        <p><Link to={`/orgs/${orgId}/sent`} className="muted small">← 보낸 요청</Link></p>
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <Status org={org.data} decisionId={decisionId} />}
      </main>
    </>
  )
}

function Status({ org, decisionId }: { org: OrgDetail; decisionId: number }) {
  const [data, reload] = useLoad<{ summary: DecisionDeliveries; groups: GroupOption[] }>(
    async () => ({ summary: await api.deliverySummary(decisionId), groups: await api.requestTargets(org.id) }),
    [decisionId, org.id],
    '요청 현황을 불러오지 못했습니다.',
  )
  const [sending, setSending] = useState(false)
  const chair = isOwner(org)

  if (data.status === 'loading') return <p className="page-message">불러오는 중…</p>
  if (data.status === 'error') return <p className="error">{data.message}</p>
  const { summary, groups } = data.data
  const sentTo = new Set(summary.targets.map((t) => t.toOrgId))
  const remaining = groups.filter((c) => !sentTo.has(c.id))

  return (
    <>
      <section className="org-header">
        <span className="chip">{summary.agenda}</span>
        <h2>{summary.summary}</h2>
        <p className="tally">{summary.targets.length === 0 ? '아직 요청하지 않았습니다.' : tallyText(summary.tally)}</p>
        {summary.targets.length > 0 && <TallyBar summary={summary} />}
      </section>

      {chair && remaining.length > 0 && !sending && (
        <button type="button" className="primary inline" onClick={() => setSending(true)}>다른 그룹에 업무 요청</button>
      )}
      {sending && (
        <SendForm
          decisionId={decisionId}
          remaining={remaining}
          onDone={() => {
            setSending(false)
            reload()
          }}
          onCancel={() => setSending(false)}
        />
      )}

      <div className="stack">
        {summary.targets.map((t) => (
          <TargetCard key={`${t.id}-${t.version}`} delivery={t} chair={chair} onChanged={reload} />
        ))}
      </div>
    </>
  )
}

/** 완료 비율 막대. 분모는 유효 대상. */
function TallyBar({ summary }: { summary: DecisionDeliveries }) {
  const { total, counts } = summary.tally
  if (total === 0) return null
  const done = Math.round((counts.DONE / total) * 100)
  return (
    <div className="tally-bar" role="img" aria-label={`완료 ${counts.DONE} / 전체 ${total}`}>
      <div className="tally-bar-fill" style={{ width: `${done}%` }} />
    </div>
  )
}

function SendForm({ decisionId, remaining, onDone, onCancel }: {
  decisionId: number
  remaining: GroupOption[]
  onDone: () => void
  onCancel: () => void
}) {
  // 모든 그룹이 후보라서 처음에는 아무것도 고르지 않는다.
  const [targets, setTargets] = useState<Set<number>>(() => new Set())
  const [requestType, setRequestType] = useState<RequestType>('COOPERATE')
  const [dueDate, setDueDate] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function toggle(id: number) {
    const next = new Set(targets)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setTargets(next)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.sendDeliveries(decisionId, {
        targetOrgIds: [...targets],
        requestType,
        dueDate: dueDate || null,
        message,
      })
      onDone()
    } catch (e) {
      setError(errorMessage(e, '요청하지 못했습니다.'))
      setBusy(false)
    }
  }

  return (
    <form className="card form-card" onSubmit={submit}>
      <h3>다른 그룹에 업무 요청</h3>
      <fieldset className="checks">
        <legend className="small">받을 그룹</legend>
        {remaining.map((c) => (
          <label key={c.id} className="check-row">
            <input type="checkbox" checked={targets.has(c.id)} onChange={() => toggle(c.id)} />
            {c.name}
          </label>
        ))}
      </fieldset>
      <label className="compact">
        요청 종류
        <select value={requestType} onChange={(e) => setRequestType(e.target.value as RequestType)}>
          <option value="COOPERATE">협조 요청 (결과 제출 필요)</option>
          <option value="RESULT_NOTICE">결과 보고 요청 (결과 제출 필요)</option>
          <option value="INFORM">알림 (확인만)</option>
        </select>
      </label>
      <label className="compact">
        기한
        <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
      </label>
      <label className="compact">
        요청 사항
        <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={3} maxLength={2000}
                  placeholder="받는 학생회가 무엇을 하면 되는지 적어 주세요." />
      </label>
      <p className="muted small">받는 그룹에는 결정 요약과 요청 사항만 보이고, 우리 회의록은 보이지 않습니다.</p>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="submit" className="primary inline" disabled={busy || targets.size === 0}>
          {targets.size}곳에 요청
        </button>
        <button type="button" className="ghost" onClick={onCancel}>취소</button>
      </div>
    </form>
  )
}

function TargetCard({ delivery: d, chair, onChanged }: { delivery: DeliveryView; chair: boolean; onChanged: () => void }) {
  const [dueDate, setDueDate] = useState(d.dueDate ?? '')
  const [reply, setReply] = useState('')
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const finished = d.status === 'DONE' || d.status === 'NOT_APPLICABLE' || d.status === 'CANCELLED'
  const latest = d.submissions[0]
  const reviewable = chair && d.status === 'SUBMITTED' && latest?.reviewStatus === 'PENDING'

  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'VERSION_CONFLICT'
        ? '받은 그룹이 먼저 바꿨습니다. 최신 상태를 다시 불러왔습니다.'
        : errorMessage(e, fallback))
      if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') onChanged()
      setBusy(false)
    }
  }
  const patch = (change: DeliveryPatch, fallback: string) => run(() => api.updateDelivery(d, change), fallback)

  function cancel() {
    const reason = window.prompt(`${d.toOrgName}에 보낸 요청을 취소할까요? 취소 사유를 적어 주세요.`)
    if (reason !== null) patch({ status: 'CANCELLED', reason }, '취소하지 못했습니다.')
  }

  return (
    <article className="card target-card">
      <div className="card-title-row">
        <strong>{d.toOrgName}</strong>
        <span className={`chip delivery-${d.status.toLowerCase()}`}>{DELIVERY_STATUS_LABEL[d.status]}</span>
      </div>
      <p className="muted small">
        {REQUEST_TYPE_LABEL[d.requestType]} · 기한 {d.dueDate ?? '없음'}
        {d.internalDepartmentName && ` · 담당 ${d.internalDepartmentName}`}
      </p>
      {d.closingReason && finished && <p className="muted small">사유: {d.closingReason}</p>}

      {d.status === 'CLARIFICATION_REQUESTED' && (
        <div className="notice small">
          <p>확인 요청: {d.clarificationReason}</p>
          {chair && (
            <>
              <label className="compact">
                답변
                <textarea value={reply} onChange={(e) => setReply(e.target.value)} rows={2} maxLength={2000} />
              </label>
              <button type="button" className="primary inline" disabled={busy || !reply.trim()}
                      onClick={() => patch({ status: 'PENDING', clarificationReply: reply }, '답변을 보내지 못했습니다.')}>
                답변 보내기
              </button>
            </>
          )}
        </div>
      )}

      {d.submissions.length > 0 && (
        <ul className="submissions">
          {d.submissions.map((s) => (
            <li key={s.id}>
              <span className={`chip review-${s.reviewStatus.toLowerCase()}`}>
                {s.reviewStatus === 'PENDING' ? '검토 대기' : s.reviewStatus === 'APPROVED' ? '확정' : '반려'}
              </span>{' '}
              {s.resultLink && <a href={s.resultLink} target="_blank" rel="noreferrer">결과 링크</a>}
              {s.description && <span> {s.description}</span>}
              <span className="muted small block">
                {s.submittedByName} · {formatDateTime(s.createdAt)}
                {s.reviewComment && ` · 의견: ${s.reviewComment}`}
              </span>
            </li>
          ))}
        </ul>
      )}

      {reviewable && (
        <div className="panel">
          <label className="compact">
            의견 (반려할 때는 필수)
            <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={2} maxLength={2000} />
          </label>
          <div className="actions-row">
            <button type="button" className="primary inline" disabled={busy}
                    onClick={() => run(() => api.reviewSubmission(latest.id, true, comment), '확정하지 못했습니다.')}>
              완료 확정
            </button>
            <button type="button" className="ghost" disabled={busy || !comment.trim()}
                    onClick={() => run(() => api.reviewSubmission(latest.id, false, comment), '반려하지 못했습니다.')}>
              반려
            </button>
          </div>
        </div>
      )}

      {chair && !finished && (
        <div className="actions-row">
          <label className="compact inline-label">
            기한
            <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
          </label>
          {(dueDate || null) !== d.dueDate && (
            <button type="button" className="ghost" disabled={busy}
                    onClick={() => patch({ dueDate: dueDate || null }, '기한을 바꾸지 못했습니다.')}>
              기한 변경
            </button>
          )}
          <button type="button" className="ghost danger" disabled={busy} onClick={cancel}>요청 취소</button>
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </article>
  )
}
