import { useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { api, type ClauseView, type RuleDocDetail } from '../api'
import { formatDateTime } from '../labels'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { RULE_STATUS_LABEL } from './RulesPage'
import { TopBar } from './TopBar'

type Filter = 'all' | 'unreviewed' | 'flagged'

const KIND_LABEL: Record<ClauseView['kind'], string> = {
  PREAMBLE: '머리말',
  CHAPTER: '장',
  SECTION: '절',
  ARTICLE: '조',
  EXTRA: '보칙',
  SUPPLEMENTARY: '부칙',
}

/** 검수 대상(조·보칙·부칙)인가. 장·절 제목과 머리말은 검수할 것이 없다. */
function reviewable(c: ClauseView) {
  return c.kind === 'ARTICLE' || c.kind === 'EXTRA' || c.kind === 'SUPPLEMENTARY'
}

/**
 * 규정 검수 화면. 자동으로 나눈 조항을 원문과 대조해 확인하고, 필요하면 고치거나 추가·삭제한 뒤
 * 모든 조항을 '검수 완료'로 표시하면 확정할 수 있다. 확정한 규정은 고치지 않는다.
 */
export function RuleDocumentPage() {
  const orgId = Number(useParams().orgId)
  const docId = Number(useParams().docId)
  const [org] = useOrg(orgId)
  const [doc, reload] = useLoad<RuleDocDetail>(() => api.ruleDocument(docId), [docId], '규정을 불러오지 못했습니다.')
  const owner = org.status === 'ready' && isOwner(org.data)

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}/rules`} className="muted small">← 규정 목록</Link></p>
        {doc.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {doc.status === 'error' && <p className="error" role="alert">{doc.message}</p>}
        {doc.status === 'ready' && <Review orgId={orgId} doc={doc.data} owner={owner} reload={reload} />}
      </main>
    </>
  )
}

function Review({ orgId, doc, owner, reload }: { orgId: number; doc: RuleDocDetail; owner: boolean; reload: () => void }) {
  const location = useLocation()
  const navigate = useNavigate()
  const s = doc.summary
  const draft = s.status === 'DRAFT'
  const editable = owner && draft
  const [filter, setFilter] = useState<Filter>(draft ? 'unreviewed' : 'all')
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const arrivalWarnings = (location.state as { warnings?: string[] } | null)?.warnings ?? []

  const shown = useMemo(() => doc.clauses.filter((c) => {
    if (!reviewable(c)) return filter === 'all' && !query
    if (filter === 'unreviewed' && c.reviewStatus !== 'UNREVIEWED') return false
    if (filter === 'flagged' && c.flags.length === 0) return false
    if (query && !`${c.label} ${c.title ?? ''} ${c.text}`.includes(query)) return false
    return true
  }), [doc.clauses, filter, query])

  async function run(action: () => Promise<unknown>, after?: () => void) {
    setBusy(true)
    setError(null)
    try {
      await action()
      reload()
      after?.()
    } catch (e) {
      setError(errorMessage(e, '요청을 처리하지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  const visibleUnreviewed = shown.filter((c) => reviewable(c) && c.reviewStatus === 'UNREVIEWED')
  const warnings = [...new Set([...arrivalWarnings, ...doc.registrationWarnings])]

  return (
    <>
      <h2>{s.title} <span className={s.status === 'CONFIRMED' ? 'chip' : 'chip subtle'}>{RULE_STATUS_LABEL[s.status]}</span></h2>
      <p className="muted small">
        {s.issuer}{doc.appliesTo && ` · ${doc.appliesTo}`} · 조항 {s.articleCount}개
        {draft && ` · 검수 남음 ${s.unreviewedCount}개`}
        {s.status === 'CONFIRMED' && doc.reviewedAt && ` · ${formatDateTime(doc.reviewedAt)} 확정`}
      </p>
      {!s.roundtripOk && (
        <p className="error" role="alert">나눈 조항을 이어 붙인 결과가 원문과 달랐습니다. 분리 결과를 원문과 대조해 주세요.</p>
      )}
      {warnings.map((w) => <p key={w} className="error" role="alert">{w}</p>)}
      {doc.notes && <p className="card small">메모: {doc.notes}</p>}
      {doc.sourceSha256 && (
        <p className="muted small">원본 파일 지문(SHA-256): {doc.sourceSha256.slice(0, 16)}… {doc.fileName && `(${doc.fileName})`}</p>
      )}

      {editable && <MetadataForm doc={doc} onSaved={reload} />}
      {!editable && (
        <p className="muted small">시행일 {s.effectiveDate ?? '미입력'} · 버전 {s.docVersion ?? '미입력'}</p>
      )}

      <div className="actions-row">
        <select value={filter} onChange={(e) => setFilter(e.target.value as Filter)} aria-label="보기">
          <option value="all">모두</option>
          <option value="unreviewed">검수 안 한 조항</option>
          <option value="flagged">주의 표시가 있는 조항</option>
        </select>
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="조항 안에서 찾기" aria-label="조항 찾기" />
      </div>

      {editable && visibleUnreviewed.length > 0 && (
        <button type="button" className="ghost" disabled={busy}
                onClick={() => {
                  if (window.confirm(`지금 보이는 검수 안 한 조항 ${visibleUnreviewed.length}개를 모두 검수 완료로 표시할까요? 원문과 대조해 읽어 본 조항만 표시하세요.`)) {
                    void run(() => api.reviewClauses(s.id, visibleUnreviewed.map((c) => c.id)))
                  }
                }}>
          지금 보이는 {visibleUnreviewed.length}개 검수 완료로 표시
        </button>
      )}
      {error && <p className="error" role="alert">{error}</p>}

      <ul className="card-list">
        {shown.map((c) => (
          <ClauseCard key={`${c.id}-${c.version}`} clause={c} editable={editable} busy={busy} run={run} docId={s.id} />
        ))}
        {shown.length === 0 && <li className="empty">보여 줄 조항이 없습니다.</li>}
      </ul>

      {owner && draft && (
        <section className="card stack">
          <h3>확정</h3>
          <p className="muted small">
            발행 주체·시행일·규정 버전이 있고 모든 조·보칙·부칙을 검수 완료해야 확정할 수 있습니다. 확정한 규정은 고칠 수 없고,
            바뀌면 새로 올립니다.
          </p>
          <div className="actions-row">
            <button type="button" className="primary inline" disabled={busy || s.unreviewedCount > 0 || !s.effectiveDate || !s.docVersion}
                    onClick={() => run(() => api.confirmRuleDocument(s))}>
              확정하기
            </button>
            <button type="button" className="ghost danger" disabled={busy}
                    onClick={() => {
                      if (window.confirm('검수 중인 이 규정을 지울까요? 되돌릴 수 없습니다.')) {
                        void run(() => api.deleteRuleDocument(s.id), () => navigate(`/orgs/${orgId}/rules`))
                      }
                    }}>
              이 규정 지우기
            </button>
          </div>
        </section>
      )}
      {owner && s.status === 'CONFIRMED' && (
        <button type="button" className="ghost danger" disabled={busy}
                onClick={() => {
                  if (window.confirm('이 규정을 폐기할까요? 더 이상 지출 검토의 근거로 쓰이지 않습니다.')) {
                    void run(() => api.retireRuleDocument(s))
                  }
                }}>
          폐기
        </button>
      )}
    </>
  )
}

function MetadataForm({ doc, onSaved }: { doc: RuleDocDetail; onSaved: () => void }) {
  const s = doc.summary
  const [issuer, setIssuer] = useState(s.issuer)
  const [effectiveDate, setEffectiveDate] = useState(s.effectiveDate ?? '')
  const [docVersion, setDocVersion] = useState(s.docVersion ?? '')
  const [notes, setNotes] = useState(doc.notes ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await api.updateRuleDocument(s, { issuer, effectiveDate, docVersion, notes })
      onSaved()
    } catch (e) {
      setError(errorMessage(e, '저장하지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card stack">
      <h3>문서 정보</h3>
      <label>발행 주체
        <input value={issuer} onChange={(e) => setIssuer(e.target.value)} />
      </label>
      <label>시행일
        <input type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} />
      </label>
      <label>규정 버전
        <input value={docVersion} onChange={(e) => setDocVersion(e.target.value)} />
      </label>
      <label>메모
        <textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="button" className="ghost" onClick={save} disabled={busy}>저장</button>
      </div>
    </section>
  )
}

function ClauseCard({ clause, editable, busy, run, docId }: {
  clause: ClauseView
  editable: boolean
  busy: boolean
  run: (action: () => Promise<unknown>, after?: () => void) => Promise<void>
  docId: number
}) {
  const [editing, setEditing] = useState(false)
  const [adding, setAdding] = useState(false)
  const [label, setLabel] = useState(clause.label)
  const [title, setTitle] = useState(clause.title ?? '')
  const [text, setText] = useState(clause.text)
  const [newLabel, setNewLabel] = useState('')
  const [newText, setNewText] = useState('')

  if (!reviewable(clause)) {
    return (
      <li className="card">
        <strong className="muted">{clause.label}</strong>
      </li>
    )
  }
  const reviewed = clause.reviewStatus === 'REVIEWED'

  return (
    <li className="card">
      <div className="card-head">
        <strong>{clause.label}{clause.title && clause.kind === 'ARTICLE' ? ` (${clause.title})` : ''}</strong>
        <span className="chips">
          <span className="chip subtle">{KIND_LABEL[clause.kind]}</span>
          {clause.edited && <span className="chip subtle">사람이 고침</span>}
          <span className={reviewed ? 'chip' : 'chip warn'}>{reviewed ? '검수 완료' : '검수 전'}</span>
        </span>
      </div>
      {clause.chapter && <p className="muted small">{clause.chapter}</p>}
      {clause.flags.map((f) => <p key={f} className="error small">{f}</p>)}
      {!editing && <pre className="clause-text">{clause.text}</pre>}
      {editing && (
        <div className="stack">
          <label>조항 번호
            <input value={label} onChange={(e) => setLabel(e.target.value)} />
          </label>
          <label>제목
            <input value={title} onChange={(e) => setTitle(e.target.value)} />
          </label>
          <label>본문 (원문 그대로 두고 잘못 나뉜 부분만 고치세요)
            <textarea rows={10} value={text} onChange={(e) => setText(e.target.value)} />
          </label>
          <div className="actions-row">
            <button type="button" className="primary inline" disabled={busy}
                    onClick={() => run(() => api.updateClause(clause, { label, title, text }), () => setEditing(false))}>
              저장
            </button>
            <button type="button" className="ghost" onClick={() => setEditing(false)}>취소</button>
          </div>
        </div>
      )}
      {editable && !editing && (
        <div className="actions-row">
          <button type="button" className={reviewed ? 'ghost' : 'primary inline'} disabled={busy}
                  onClick={() => run(() => api.updateClause(clause, { reviewed: !reviewed }))}>
            {reviewed ? '검수 취소' : '검수 완료'}
          </button>
          <button type="button" className="ghost" onClick={() => setEditing(true)}>수정</button>
          <button type="button" className="ghost" onClick={() => setAdding(!adding)}>아래에 조항 추가</button>
          <button type="button" className="ghost danger" disabled={busy}
                  onClick={() => {
                    if (window.confirm(`${clause.label}을(를) 지울까요? 기록은 남습니다.`)) {
                      void run(() => api.deleteClause(clause.id))
                    }
                  }}>
            삭제
          </button>
        </div>
      )}
      {adding && (
        <div className="stack">
          <label>새 조항 번호
            <input value={newLabel} onChange={(e) => setNewLabel(e.target.value)} placeholder="예: 제12조" />
          </label>
          <label>본문
            <textarea rows={5} value={newText} onChange={(e) => setNewText(e.target.value)} />
          </label>
          <button type="button" className="primary inline" disabled={busy || !newLabel.trim() || !newText.trim()}
                  onClick={() => run(
                    () => api.addClause(docId, { afterClauseId: clause.id, label: newLabel, text: newText }),
                    () => { setAdding(false); setNewLabel(''); setNewText('') },
                  )}>
            추가
          </button>
        </div>
      )}
    </li>
  )
}
