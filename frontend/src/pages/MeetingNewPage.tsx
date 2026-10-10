import { useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { errorMessage } from '../useOrg'
import { TopBar } from './TopBar'
import { FlowIcon } from './MeetingFlow'

/** datetime-local 입력의 기본값(지금, 내 PC 시간대) */
function nowLocal(): string {
  const d = new Date()
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset())
  return d.toISOString().slice(0, 16)
}

export function MeetingNewPage() {
  const orgId = Number(useParams().orgId)
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [heldAt, setHeldAt] = useState(nowLocal)
  const [transcript, setTranscript] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [reading, setReading] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  /** .txt 는 브라우저에서 바로 읽고, .pdf 는 서버가 글자만 뽑아 준다(원본 PDF는 저장하지 않음). */
  async function loadFile(event: ChangeEvent<HTMLInputElement>) {
    const input = event.target
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    setError(null)
    setNotice(null)
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
    if (!isPdf) {
      setTranscript(await file.text())
      return
    }
    setReading(true)
    try {
      const extracted = await api.extractPdf(orgId, file)
      setTranscript(extracted.text)
      if (!title) setTitle(file.name.replace(/\.pdf$/i, ''))
      setNotice(`PDF ${extracted.pageCount}쪽에서 글자를 뽑았습니다. 줄바꿈이나 표가 어색하면 고친 뒤 올려 주세요.`)
    } catch (e) {
      setError(errorMessage(e, 'PDF를 읽지 못했습니다.'))
    } finally {
      setReading(false)
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const created = await api.createMeeting({
        orgId,
        title,
        heldAt: new Date(heldAt).toISOString(),
        transcript,
      })
      await api.analyze(created.meeting.id)
      navigate(`/orgs/${orgId}/meetings/${created.meeting.id}`)
    } catch (e) {
      setError(errorMessage(e, '회의를 올리지 못했습니다.'))
      setSubmitting(false)
    }
  }

  const lineCount = transcript.split('\n').filter((l) => l.trim()).length

  return (
    <>
      <TopBar />
      <main className="page narrow meeting-new-page">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        <span className="eyebrow">회의 → 결정 → 부서별 업무</span>
        <h2>회의에서 다음 할 일을 꺼내세요.</h2>
        <div className="source-options">
          <div className="source-option chosen"><FlowIcon kind="meeting" /><strong>회의록으로 시작</strong><span>텍스트 입력 · TXT · PDF</span></div>
          <div className="source-option upcoming"><FlowIcon kind="audio" /><strong>녹음으로 시작 <span className="chip subtle">준비 중</span></strong><span>녹음 업로드·전사는 아직 연결되지 않았습니다.</span></div>
        </div>
        <p className="muted small">
          회의록을 입력하거나 파일로 불러오세요. AI가 결정사항과 부서별 업무를 제안합니다.
          AI는 결정·업무 <strong>후보</strong>만 만들고, 회장이 승인해야 확정됩니다.
        </p>
        <form onSubmit={onSubmit} className="card">
          <label>
            회의 이름
            <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} required />
          </label>
          <label>
            회의 일시
            <input type="datetime-local" value={heldAt} onChange={(e) => setHeldAt(e.target.value)} required />
          </label>
          <label>
            회의록
            <textarea
              value={transcript}
              onChange={(e) => setTranscript(e.target.value)}
              rows={14}
              placeholder={'회장: 오늘 회의 시작하겠습니다.\n기획부장: 축제 부스 일정 보고드립니다.'}
              required
            />
          </label>
          <div className="actions-row between">
            <label className="file-label">
              <input type="file" accept=".txt,.pdf,text/plain,application/pdf" onChange={loadFile} disabled={reading} />
              <span className="ghost">{reading ? 'PDF 읽는 중…' : '파일에서 불러오기 (.txt, .pdf)'}</span>
            </label>
            <span className="muted small">발언 {lineCount}줄</span>
          </div>
          {notice && <p className="notice small" role="status">{notice}</p>}
          {error && <p className="error" role="alert">{error}</p>}
          <button type="submit" className="primary" disabled={submitting || reading}>
            {submitting ? '올리는 중…' : '결정·업무 후보 만들기'}
          </button>
        </form>
      </main>
    </>
  )
}
