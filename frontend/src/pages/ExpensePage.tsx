import './expense.css'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import {
  api,
  type DecisionOption,
  type ExpenseChecklistItem,
  type ExpenseClauseRef,
  type ExpenseRestriction,
  type ExpenseReviewStatus,
  type ExpenseReviewSummary,
  type ExpenseReviewView,
} from '../api'
import { formatDateTime } from '../labels'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { TopBar } from './TopBar'

export const EXPENSE_STATUS_LABEL: Record<ExpenseReviewStatus, string> = {
  CANDIDATES_FOUND: '후보 조항 있음',
  NEEDS_CHECK: '확인 필요',
  PROCEDURE_FOUND: '절차 확인',
  POSSIBLE_CONFLICT: '충돌 가능',
  POSSIBLY_RESTRICTED: '금지 가능성',
  REVIEWED: '검토 완료',
}

/** 상태 배지 색: 확인 필요·충돌 가능은 눈에 띄게, 검토 완료는 조용하게 */
function statusChip(status: ExpenseReviewStatus): string {
  if (status === 'NEEDS_CHECK' || status === 'POSSIBLE_CONFLICT' || status === 'POSSIBLY_RESTRICTED') return 'chip warn'
  if (status === 'REVIEWED') return 'chip subtle'
  return 'chip'
}

function won(amount: number | null) {
  return amount == null ? '금액 미정' : `${amount.toLocaleString('ko-KR')}원`
}

/**
 * 지출 절차 검토 목록과 새 요청.
 * 요청하면 이 그룹이 확정한 규정에서 관련 조항을 찾아 보여 준다. 찾은 조항은 후보일 뿐이며,
 * 적용 여부와 감사 결과는 사람이 판단한다. 이 화면은 감사를 대체하지 않는다.
 */
export function ExpensePage() {
  const orgId = Number(useParams().orgId)
  const [org] = useOrg(orgId)
  const [reviews] = useLoad<ExpenseReviewSummary[]>(
    () => api.expenseReviews(orgId), [orgId], '지출 검토를 불러오지 못했습니다.')
  const canRequest = org.status === 'ready' && (org.data.myRole === 'OWNER' || org.data.myRole === 'EDITOR')

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        <h2>지출 절차 확인</h2>
        <p className="muted small">
          지출 항목을 적으면 이 그룹이 확정한 규정에서 관련 조항을 찾아 원문과 함께 보여 줍니다.
          AI 는 후보 조항 안에서만 필요한 승인과 증빙을 정리하며, 조항 원문은 규정에 등록된 글 그대로 보여 줍니다.
          찾은 조항은 후보일 뿐이며 감사를 대체하지 않습니다.
        </p>
        {canRequest && <NewForm orgId={orgId} />}
        {!canRequest && org.status === 'ready' && <p className="muted small">지출 검토 요청은 회장과 부장급 직책이 할 수 있습니다.</p>}
        {reviews.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {reviews.status === 'error' && <p className="error" role="alert">{reviews.message}</p>}
        {reviews.status === 'ready' && reviews.data.length === 0 && <p className="empty">아직 요청한 지출 검토가 없습니다.</p>}
        {reviews.status === 'ready' && reviews.data.length > 0 && (
          <ul className="card row-list">
            {reviews.data.map((r) => (
              <li key={r.id}>
                <Link to={`/orgs/${orgId}/expense/${r.id}`} className="row-link">
                  <span>
                    <strong>{r.item}</strong>
                    <span className="muted small block">
                      {won(r.amount)} · 후보 조항 {r.candidateCount}개 · {formatDateTime(r.createdAt)}
                    </span>
                  </span>
                  <span className={statusChip(r.status)}>{EXPENSE_STATUS_LABEL[r.status]}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </main>
    </>
  )
}

function NewForm({ orgId }: { orgId: number }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  // 승인된 결정의 지출 언급으로 값을 미리 채운다(사람이 보고 고친 뒤 요청한다). 주소에 값이 있거나 아래 선택 상자로 고른다.
  const [decisionId, setDecisionId] = useState<number | undefined>(Number(params.get('decisionId')) || undefined)
  const [decisions] = useLoad<DecisionOption[]>(
    () => api.approvedDecisions(orgId), [orgId], '승인된 결정을 불러오지 못했습니다.')
  const withExpense = decisions.status === 'ready' ? decisions.data.filter((d) => d.expenseProposal) : []
  const [open, setOpen] = useState(decisionId !== undefined)
  const [item, setItem] = useState(params.get('item') ?? '')
  const [amount, setAmount] = useState(params.get('amount') ?? '')
  const [fundSource, setFundSource] = useState(params.get('fundSource') ?? '')
  const [plannedDate, setPlannedDate] = useState('')
  const [note, setNote] = useState('')
  const [keywords, setKeywords] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function pickDecision(value: string) {
    const picked = withExpense.find((d) => String(d.id) === value)
    setDecisionId(picked?.id)
    if (picked?.expenseProposal) {
      setItem(picked.expenseProposal.item)
      setAmount(picked.expenseProposal.amount == null ? '' : String(picked.expenseProposal.amount))
      setFundSource(picked.expenseProposal.fundSource ?? '')
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.createExpenseReview({
        orgId,
        decisionId,
        item,
        amount: amount.trim() === '' ? undefined : Number(amount.replace(/[,\s원]/g, '')),
        fundSource: fundSource || undefined,
        plannedDate: plannedDate || undefined,
        note: note || undefined,
        keywords: keywords || undefined,
      })
      navigate(`/orgs/${orgId}/expense/${created.id}`)
    } catch (err) {
      setError(errorMessage(err, '요청하지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return <button type="button" className="primary inline" onClick={() => setOpen(true)}>지출 절차 확인하기</button>
  }
  return (
    <form className="card stack" onSubmit={submit}>
      <h3>지출 절차 확인하기</h3>
      {withExpense.length > 0 && (
        <label>승인된 결정에서 불러오기 (선택)
          <select value={decisionId ?? ''} onChange={(e) => pickDecision(e.target.value)}>
            <option value="">직접 입력</option>
            {withExpense.map((d) => (
              <option key={d.id} value={d.id}>{d.meetingTitle} · {d.expenseProposal?.item}</option>
            ))}
          </select>
        </label>
      )}
      {decisionId !== undefined && (
        <p className="muted small">승인된 결정의 지출 언급으로 채웠습니다. 내용이 맞는지 확인해 주세요.</p>
      )}
      <label>지출 항목
        <input value={item} onChange={(e) => setItem(e.target.value)} maxLength={200} required
               placeholder="예: MT 숙박비, 간식 구입" />
      </label>
      <label>금액 (원, 모르면 비워 두기)
        <input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="예: 300000" />
      </label>
      <label>재원 (선택)
        <input value={fundSource} onChange={(e) => setFundSource(e.target.value)} maxLength={100} placeholder="예: 학생회비" />
      </label>
      <label>집행 예정일 (선택, 비우면 오늘 시행 중인 규정에서 찾음)
        <input type="date" value={plannedDate} onChange={(e) => setPlannedDate(e.target.value)} />
      </label>
      <label>설명 (선택)
        <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} />
      </label>
      <label>규정에서 더 찾을 말 (선택, 띄어쓰기로 구분)
        <input value={keywords} onChange={(e) => setKeywords(e.target.value)} maxLength={200} placeholder="예: 영수증 결재 사전승인" />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="submit" className="primary inline" disabled={busy || !item.trim()}>{busy ? '찾는 중…' : '관련 조항 찾기'}</button>
        <button type="button" className="ghost" onClick={() => setOpen(false)} disabled={busy}>취소</button>
      </div>
    </form>
  )
}

/** 요청 한 건의 결과: AI 안내(서버가 조항 원문에서 확인한 것만), 체크리스트, 후보 조항(원문 그대로), 검토 완료 */
export function ExpenseReviewPage() {
  const orgId = Number(useParams().orgId)
  const reviewId = Number(useParams().reviewId)
  const [org] = useOrg(orgId)
  const [review, reload] = useLoad<ExpenseReviewView>(
    () => api.expenseReview(reviewId), [reviewId], '지출 검토를 불러오지 못했습니다.')
  const readOnly = org.status !== 'ready' || org.data.adminView
  const canWork = !readOnly && org.status === 'ready' && (org.data.myRole === 'OWNER' || org.data.myRole === 'EDITOR')
  // 검토 완료는 지금은 회장만. 서버가 같은 규칙으로 막는다.
  const canComplete = !readOnly && org.status === 'ready' && isOwner(org.data)

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}/expense`} className="muted small">← 지출 절차 확인</Link></p>
        {review.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {review.status === 'error' && <p className="error" role="alert">{review.message}</p>}
        {review.status === 'ready' && (
          <Result review={review.data} orgId={orgId} canWork={canWork} canComplete={canComplete} reload={reload} />
        )}
      </main>
    </>
  )
}

interface ResultProps {
  review: ExpenseReviewView
  orgId: number
  canWork: boolean
  canComplete: boolean
  reload: () => void
}

function scrollToClause(clauseId: number) {
  document.getElementById(`clause-${clauseId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function ClauseLinks({ clauses }: { clauses: ExpenseClauseRef[] }) {
  return (
    <span className="chips">
      {clauses.map((c) => (
        <button key={c.clauseId} type="button" className="chip subtle expense-link" onClick={() => scrollToClause(c.clauseId)}
                title={`${c.docTitle} ${c.label}로 이동`}>
          {c.label}
        </button>
      ))}
    </span>
  )
}

function Result({ review, orgId, canWork, canComplete, reload }: ResultProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const reviewed = review.status === 'REVIEWED'
  const jobActive = review.job?.status === 'QUEUED' || review.job?.status === 'RUNNING'
  const jobFailed = review.job?.status === 'FAILED' || review.job?.status === 'FAILED_UNKNOWN'

  // AI 안내를 만드는 중이면 3초마다 새로 불러온다.
  useEffect(() => {
    if (!jobActive) return
    const timer = window.setInterval(reload, 3000)
    return () => window.clearInterval(timer)
  }, [jobActive, reload])

  async function run(action: () => Promise<unknown>, failure: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(errorMessage(err, failure))
    } finally {
      setBusy(false)
      reload()
    }
  }

  const approvals = review.checklist.filter((i) => i.kind === 'APPROVAL')
  const evidence = review.checklist.filter((i) => i.kind === 'EVIDENCE')
  const guidance = review.guidance
  const editable = canWork && !reviewed
  const canAskAgain = editable && review.candidates.length > 0
  const canReview = canComplete && !reviewed && guidance !== null && !jobActive
  const toggle = (item: ExpenseChecklistItem) =>
    run(() => api.checkExpenseItem(item, !item.checked), '체크를 바꾸지 못했습니다.')

  return (
    <>
      <h2>
        {review.item}{' '}
        <span className={statusChip(review.status)}>{EXPENSE_STATUS_LABEL[review.status]}</span>
      </h2>
      <p className="muted small">
        {won(review.amount)}
        {review.fundSource && ` · 재원 ${review.fundSource}`}
        {review.plannedDate && ` · 집행 예정 ${review.plannedDate}`}
        {review.decisionId && ' · 승인된 결정에서 요청'}
      </p>
      {review.note && <p className="card small">{review.note}</p>}

      <p className="card warn-card small" role="note"><strong>{review.disclaimer}</strong></p>
      {review.notices.map((n) => <p key={n} className="error small" role="alert">{n}</p>)}
      {reviewed && (
        <p className="card small" role="status">
          검토 완료{review.reviewedAt && ` (${formatDateTime(review.reviewedAt)})`}: 회장이 AI 안내와 조항 원문을 확인했다는
          표시입니다. 이 지출이 규정에 맞다는 뜻은 아닙니다.
          {guidance && ` 확인 당시 AI 안내 결과: ${EXPENSE_STATUS_LABEL[guidance.outcome]}`}
        </p>
      )}

      {/* AI 안내 진행·실패. 실패해도 아래 후보 조항은 그대로 보인다. */}
      {jobActive && (
        <p className="muted small" role="status">
          AI 가 후보 조항에서 필요한 승인과 증빙을 정리하는 중입니다… (3초마다 새로 불러옵니다)
        </p>
      )}
      {!jobActive && jobFailed && review.job && (
        <p className="error small" role="alert">
          {review.job.status === 'FAILED_UNKNOWN'
            ? 'AI 서버가 요청을 처리했는지 알 수 없어 자동으로 다시 부르지 않았습니다.'
            : 'AI 안내를 받지 못했습니다.'}
          {' '}오류 종류: {review.job.lastError ?? '알 수 없음'}. 후보 조항은 아래에 그대로 있습니다.
        </p>
      )}
      {!jobActive && !guidance && !jobFailed && review.candidates.length > 0 && (
        <p className="muted small">아직 AI 안내가 없습니다. 아래 후보 조항 원문을 직접 확인해 주세요.</p>
      )}

      {guidance && (
        <>
          {guidance.restrictions.some((r) => r.related) && (
            <>
              <h3>사용 금지·제한</h3>
              <p className="muted small">
                후보 조항 중 이 지출의 사용을 막거나 한도·조건을 두는 내용입니다. 규정에 따를 절차가 있다는 뜻이 아닙니다.
                이 지출이 실제로 여기에 해당하는지는 조항 원문으로 직접 확인해 주세요.
              </p>
              <Restrictions items={guidance.restrictions.filter((r) => r.related)} />
            </>
          )}
          {guidance.restrictions.some((r) => !r.related) && (
            <details className="card small">
              <summary>
                지출 항목과 직접 관련이 없어 보이는 다른 금지·제한 {guidance.restrictions.filter((r) => !r.related).length}개 (참고)
              </summary>
              <p className="muted small">
                같은 조항에 함께 있어 AI 가 함께 낸 내용입니다. 글자가 겹치지 않아 이 지출과 관련이 없어 보이지만,
                해당하는지는 조항 원문으로 확인해 주세요.
              </p>
              <Restrictions items={guidance.restrictions.filter((r) => !r.related)} />
            </details>
          )}
          <h3>필요한 승인</h3>
          <Checklist items={approvals} editable={editable} busy={busy} onToggle={toggle}
                     empty="후보 조항에서 서버가 확인한 필요 승인이 없습니다. 조항 원문을 직접 확인해 주세요." />
          <h3>필요한 증빙</h3>
          <Checklist items={evidence} editable={editable} busy={busy} onToggle={toggle}
                     empty="후보 조항에서 서버가 확인한 필요 증빙이 없습니다. 조항 원문을 직접 확인해 주세요." />
          {guidance.setAside.length > 0 && (
            <details className="card small">
              <summary>이 지출에 해당하지 않아 보여 체크리스트에서 뺀 승인·증빙 {guidance.setAside.length}개 (참고)</summary>
              <p className="muted small">
                AI 가 한 번 더 확인했을 때 이 지출과 대상이 다르거나 인용 구절과 문구가 맞지 않는다고 본 항목입니다.
                지우지 않았으니 실제로 해당하는지는 조항 원문으로 확인해 주세요.
              </p>
              <ul className="card-list">
                {guidance.setAside.map((s) => (
                  <li key={`${s.kind}-${s.text}`} className="card">
                    <p>
                      <span className="chip">{s.kind === 'APPROVAL' ? '승인' : '증빙'}</span>{' '}
                      <strong>{s.text}</strong>{' '}
                      <span className="muted small">
                        {s.reason === 'NOT_APPLICABLE' ? '이 지출에 해당하지 않아 보임' : '인용 구절과 문구가 맞지 않아 보임'}
                      </span>
                    </p>
                    <blockquote className="expense-quote">
                      <span className="muted small">원문에서 확인된 구절</span>
                      <span className="block">{s.quote}</span>
                    </blockquote>
                    <ClauseLinks clauses={s.clauses} />
                  </li>
                ))}
              </ul>
            </details>
          )}
          {(approvals.length > 0 || evidence.length > 0) && (
            <p className="muted small">
              항목 문구는 AI 가 정리한 글이고, 아래 인용 구절만 규정 원문에서 확인된 것입니다. 문구와 인용의 뜻이 맞는지 직접 확인해 주세요.
              체크 표시는 사람이 확인했다는 기록이며, 회장과 부장급 직책이 바꿀 수 있습니다.
            </p>
          )}

          {guidance.conflicts.length > 0 && (
            <>
              <h3>충돌 가능성</h3>
              <p className="muted small">
                후보 조항끼리 다르게 말하는 부분입니다. 어느 쪽이 맞는지는 AI 가 정하지 않았습니다. 직접 확인해 주세요.
              </p>
              <ul className="card-list">
                {guidance.conflicts.map((c) => (
                  <li key={c.description} className="card warn-card">
                    <p>{c.description}</p>
                    <ClauseLinks clauses={c.clauses} />
                  </li>
                ))}
              </ul>
            </>
          )}

          {guidance.missingFacts.length > 0 && (
            <>
              <h3>더 확인해야 할 사실</h3>
              <ul className="expense-list">
                {guidance.missingFacts.map((f) => <li key={f}>{f}</li>)}
              </ul>
            </>
          )}

          {guidance.note && (
            <>
              <h3>AI 참고 메모</h3>
              <p className="card small">{guidance.note}</p>
              <p className="muted small">조항으로 확인된 내용이 아니라 AI 가 쓴 참고용 글입니다. 판단은 조항 원문으로 해 주세요.</p>
            </>
          )}

          {guidance.droppedNotes.length > 0 && (
            <div className="card small" role="status">
              <strong>
                {guidance.droppedCount > 0
                  ? `서버 검증에서 ${guidance.droppedCount}개 항목을 제외했습니다.`
                  : '서버가 AI 안내를 조정했습니다.'}
              </strong>
              <ul className="expense-list">
                {guidance.droppedNotes.map((n) => <li key={n}>{n}</li>)}
              </ul>
              <span className="muted">조항 원문에서 확인되지 않은 내용은 보여 주지 않습니다. 빠진 내용이 있을 수 있습니다.</span>
            </div>
          )}
        </>
      )}

      {error && <p className="error" role="alert">{error}</p>}
      {(canAskAgain || canReview) && (
        <div className="actions-row">
          {canAskAgain && (
            <button type="button" className="ghost" disabled={busy || jobActive}
                    onClick={() => run(() => api.requestExpenseGuidance(review.id), 'AI 안내를 요청하지 못했습니다.')}>
              {guidance || jobFailed ? 'AI 안내 다시 받기' : 'AI 안내 받기'}
            </button>
          )}
          {canReview && (
            <button type="button" className="primary inline" disabled={busy}
                    onClick={() => run(() => api.completeExpenseReview(review), '검토 완료를 표시하지 못했습니다.')}>
              검토 완료
            </button>
          )}
        </div>
      )}
      {canReview && (
        <p className="muted small">
          검토 완료는 AI 안내와 아래 조항 원문을 사람이 확인했다는 표시입니다. 규정에 맞다는 판정이 아니며,
          표시한 뒤에는 체크리스트를 바꾸거나 AI 안내를 다시 받을 수 없습니다.
        </p>
      )}
      {!canWork && !reviewed && <p className="muted small">이 화면에서는 결과를 보는 것만 할 수 있습니다.</p>}

      {review.candidates.length > 0 && (
        <>
          <h3>후보 조항 {review.candidates.length}개</h3>
          <p className="muted small">
            키워드로 찾은 후보입니다. 이 지출에 실제로 적용되는지는 사람이 판단합니다. 아래 글은 검수·확정된 규정 원문 그대로입니다.
          </p>
        </>
      )}
      <ul className="card-list">
        {review.candidates.map((c) => (
          <li key={c.clauseId} id={`clause-${c.clauseId}`} className="card">
            <div className="card-head">
              <strong>{c.label}{c.title ? ` (${c.title})` : ''}</strong>
              <span className="chips">
                <span className="chip subtle">{c.docTitle}</span>
                {c.docStatus !== 'CONFIRMED' && <span className="chip warn">폐기됨</span>}
                {c.mentionsRestriction && <span className="chip warn">금지·제한 표현 포함</span>}
              </span>
            </div>
            <p className="muted small">
              {c.docVersion && `버전 ${c.docVersion}`}{c.docEffectiveDate && ` · 시행 ${c.docEffectiveDate}`}
            </p>
            {c.matchedParagraphs.length > 0 && (
              <div className="expense-match">
                <span className="muted small">이 지출 항목과 글자가 겹치는 문단 (원문에서 그대로 잘라 옴)</span>
                {c.matchedParagraphs.map((p) => <pre key={p} className="clause-text">{p}</pre>)}
              </div>
            )}
            <pre className="clause-text">{c.text}</pre>
            <Link to={`/orgs/${orgId}/rules/${c.docId}`} className="muted small">규정 전체 보기</Link>
          </li>
        ))}
      </ul>
      {review.searchText && <p className="muted small">찾는 데 쓴 말: {review.searchText}</p>}
    </>
  )
}

function Restrictions({ items }: { items: ExpenseRestriction[] }) {
  return (
    <ul className="card-list">
      {items.map((r) => (
        <li key={`${r.kind}-${r.text}`} className="card warn-card">
          <p>
            <span className="chip warn">{r.kind === 'PROHIBITED' ? '금지' : '한도·조건'}</span>{' '}
            <strong>{r.text}</strong>
          </p>
          {r.quote !== r.text && (
            <blockquote className="expense-quote">
              <span className="muted small">원문에서 확인된 구절</span>
              <span className="block">{r.quote}</span>
            </blockquote>
          )}
          <ClauseLinks clauses={r.clauses} />
        </li>
      ))}
    </ul>
  )
}

function Checklist({ items, editable, busy, empty, onToggle }: {
  items: ExpenseChecklistItem[]
  editable: boolean
  busy: boolean
  empty: string
  onToggle: (item: ExpenseChecklistItem) => void
}) {
  if (items.length === 0) return <p className="muted small">{empty}</p>
  return (
    <ul className="card-list">
      {items.map((item) => (
        <li key={item.id} className="card">
          <label className="expense-check">
            <input type="checkbox" checked={item.checked} disabled={!editable || busy} onChange={() => onToggle(item)} />
            <span><strong>{item.text}</strong></span>
          </label>
          <blockquote className="expense-quote">
            <span className="muted small">원문에서 확인된 구절</span>
            <span className="block">{item.quote}</span>
          </blockquote>
          <ClauseLinks clauses={item.clauses} />
          {item.clauses.some((c) => c.mentionsRestriction) && (
            <p className="error small" role="note">
              근거 조항에 금지·제한 표현이 있습니다. 승인·증빙이 아니라 사용을 막는 조항일 수 있으니 원문을 확인해 주세요.
            </p>
          )}
        </li>
      ))}
    </ul>
  )
}
