import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, type RuleDocStatus, type RuleDocSummary } from '../api'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { TopBar } from './TopBar'

export const RULE_STATUS_LABEL: Record<RuleDocStatus, string> = {
  DRAFT: '검수 중',
  CONFIRMED: '확정',
  RETIRED: '폐기',
}

/**
 * 규정 목록과 새 규정 올리기.
 * 올린 파일은 "제n조" 기준으로 자동 분리되어 '검수 중'으로 저장된다.
 * 사람이 조항을 하나씩 확인하고 확정한 규정만 지출 검토의 근거가 된다.
 */
export function RulesPage() {
  const orgId = Number(useParams().orgId)
  const [org] = useOrg(orgId)
  const [docs, reload] = useLoad<RuleDocSummary[]>(() => api.ruleDocuments(orgId), [orgId], '규정을 불러오지 못했습니다.')
  const owner = org.status === 'ready' && isOwner(org.data)

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        <h2>규정</h2>
        <p className="muted small">
          회칙·세칙·규정을 올리면 조항으로 나뉩니다. 나눈 결과는 사람이 확인하기 전까지 '검수 중'이며 지출 검토에 쓰이지 않습니다.
        </p>
        {owner && <UploadForm orgId={orgId} />}
        {docs.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {docs.status === 'error' && <p className="error" role="alert">{docs.message}</p>}
        {docs.status === 'ready' && docs.data.length === 0 && <p className="empty">올린 규정이 없습니다.</p>}
        {docs.status === 'ready' && docs.data.length > 0 && (
          <ul className="card row-list">
            {docs.data.map((d) => (
              <li key={d.id}>
                <Link to={`/orgs/${orgId}/rules/${d.id}`} className="row-link">
                  <span>
                    <strong>{d.title}</strong>
                    <span className="muted small block">
                      {d.issuer} · 시행 {d.effectiveDate ?? '미입력'} · 버전 {d.docVersion ?? '미입력'} · 조항 {d.articleCount}개
                      {d.status === 'DRAFT' && ` · 검수 남음 ${d.unreviewedCount}개`}
                    </span>
                  </span>
                  <span className={d.status === 'CONFIRMED' ? 'chip' : 'chip subtle'}>{RULE_STATUS_LABEL[d.status]}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {docs.status === 'ready' && <button type="button" className="ghost" onClick={reload}>새로고침</button>}
      </main>
    </>
  )
}

function UploadForm({ orgId }: { orgId: number }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [issuer, setIssuer] = useState('')
  const [appliesTo, setAppliesTo] = useState('')
  const [effectiveDate, setEffectiveDate] = useState('')
  const [docVersion, setDocVersion] = useState('')
  const [notes, setNotes] = useState('')
  const [bodyStartLine, setBodyStartLine] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.registerRuleDocument(orgId, {
        title, issuer, appliesTo, effectiveDate, docVersion, notes, bodyStartLine, file, text,
      })
      navigate(`/orgs/${orgId}/rules/${created.summary.id}`, { state: { warnings: created.registrationWarnings } })
    } catch (err) {
      setError(errorMessage(err, '규정을 올리지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return <button type="button" className="primary inline" onClick={() => setOpen(true)}>규정 올리기</button>
  }
  return (
    <form className="card stack" onSubmit={submit}>
      <h3>규정 올리기</h3>
      <label>규정 이름 (문서 첫 줄의 제목과 같게)
        <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="예: 단국대학교 감사규정" required />
      </label>
      <label>발행 주체
        <input value={issuer} onChange={(e) => setIssuer(e.target.value)} placeholder="예: 단국대학교 총대의원회 감사위원회" required />
      </label>
      <label>적용 대상 (선택)
        <input value={appliesTo} onChange={(e) => setAppliesTo(e.target.value)} />
      </label>
      <label>시행일 (확정할 때까지 입력)
        <input type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} />
      </label>
      <label>규정 버전 (확정할 때까지 입력)
        <input value={docVersion} onChange={(e) => setDocVersion(e.target.value)} placeholder="예: 2024.08.21 일부개정" />
      </label>
      <label>메모 (선택)
        <textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)}
                  placeholder="예: 표지는 2025.11.28 개정인데 본문 머리말은 2021.3.31 시행이라 총대의원회에 확인 중" />
      </label>
      <label>PDF 또는 TXT 파일
        <input type="file" accept=".pdf,.txt,application/pdf,text/plain"
               onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </label>
      {!file && (
        <label>또는 글자를 붙여 넣기
          <textarea rows={6} value={text} onChange={(e) => setText(e.target.value)} />
        </label>
      )}
      <label>본문 시작 기준 줄 (선택, 비우면 규정 이름)
        <input value={bodyStartLine} onChange={(e) => setBodyStartLine(e.target.value)} />
      </label>
      <p className="muted small">스캔한 이미지 PDF는 글자를 읽지 못합니다. 원본 파일은 저장하지 않고, 추출한 글자와 파일 지문(SHA-256)만 남깁니다.</p>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="submit" className="primary inline" disabled={busy || (!file && !text.trim())}>
          {busy ? '나누는 중…' : '올리고 조항으로 나누기'}
        </button>
        <button type="button" className="ghost" onClick={() => setOpen(false)} disabled={busy}>취소</button>
      </div>
    </form>
  )
}
