import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, type ExpenseReviewStatus, type ExpenseReviewSummary, type ExpenseReviewView } from '../api'
import { formatDateTime } from '../labels'
import { errorMessage, useLoad, useOrg } from '../useOrg'
import { TopBar } from './TopBar'

export const EXPENSE_STATUS_LABEL: Record<ExpenseReviewStatus, string> = {
  CANDIDATES_FOUND: '후보 조항 있음',
  NEEDS_CHECK: '직접 확인 필요',
  PROCEDURE_FOUND: '절차 확인',
  POSSIBLE_CONFLICT: '충돌 가능',
  REVIEWED: '검토 완료',
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
                  <span className={r.status === 'CANDIDATES_FOUND' ? 'chip' : 'chip warn'}>{EXPENSE_STATUS_LABEL[r.status]}</span>
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
  // 승인된 결정의 지출 언급에서 넘어오면 값을 미리 채운다(사람이 보고 고친 뒤 요청한다).
  const decisionId = Number(params.get('decisionId')) || undefined
  const [open, setOpen] = useState(decisionId !== undefined)
  const [item, setItem] = useState(params.get('item') ?? '')
  const [amount, setAmount] = useState(params.get('amount') ?? '')
  const [fundSource, setFundSource] = useState(params.get('fundSource') ?? '')
  const [plannedDate, setPlannedDate] = useState('')
  const [note, setNote] = useState('')
  const [keywords, setKeywords] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

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

/** 요청 한 건의 결과: 알림, 후보 조항(원문 그대로) */
export function ExpenseReviewPage() {
  const orgId = Number(useParams().orgId)
  const reviewId = Number(useParams().reviewId)
  const [review] = useLoad<ExpenseReviewView>(
    () => api.expenseReview(reviewId), [reviewId], '지출 검토를 불러오지 못했습니다.')

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}/expense`} className="muted small">← 지출 절차 확인</Link></p>
        {review.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {review.status === 'error' && <p className="error" role="alert">{review.message}</p>}
        {review.status === 'ready' && <Result review={review.data} orgId={orgId} />}
      </main>
    </>
  )
}

function Result({ review, orgId }: { review: ExpenseReviewView; orgId: number }) {
  return (
    <>
      <h2>
        {review.item}{' '}
        <span className={review.status === 'CANDIDATES_FOUND' ? 'chip' : 'chip warn'}>{EXPENSE_STATUS_LABEL[review.status]}</span>
      </h2>
      <p className="muted small">
        {won(review.amount)}
        {review.fundSource && ` · 재원 ${review.fundSource}`}
        {review.plannedDate && ` · 집행 예정 ${review.plannedDate}`}
        {review.decisionId && ' · 승인된 결정에서 요청'}
      </p>
      {review.note && <p className="card small">{review.note}</p>}
      {review.notices.map((n) => <p key={n} className="error small" role="alert">{n}</p>)}

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
          <li key={c.clauseId} className="card">
            <div className="card-head">
              <strong>{c.label}{c.title ? ` (${c.title})` : ''}</strong>
              <span className="chips">
                <span className="chip subtle">{c.docTitle}</span>
                {c.docStatus !== 'CONFIRMED' && <span className="chip warn">폐기됨</span>}
              </span>
            </div>
            <p className="muted small">
              {c.docVersion && `버전 ${c.docVersion}`}{c.docEffectiveDate && ` · 시행 ${c.docEffectiveDate}`}
            </p>
            <pre className="clause-text">{c.text}</pre>
            <Link to={`/orgs/${orgId}/rules/${c.docId}`} className="muted small">규정 전체 보기</Link>
          </li>
        ))}
      </ul>
      {review.searchText && <p className="muted small">찾는 데 쓴 말: {review.searchText}</p>}
    </>
  )
}
