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

  const [mode, setMode] = useState<'text' | 'audio'>('text')
  const [title, setTitle] = useState('')
  const [heldAt, setHeldAt] = useState(nowLocal)

  // 텍스트 모드 상태
  const [transcript, setTranscript] = useState('')
  const [reading, setReading] = useState(false)

  // 음성 모드 상태
  const [audioFile, setAudioFile] = useState<File | null>(null)
  const [consentConfirmed, setConsentConfirmed] = useState(false)

  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
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

  function handleAudioFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) {
      setAudioFile(file)
      if (!title) setTitle(file.name.replace(/\.[^/.]+$/, ''))
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)

    try {
      if (mode === 'text') {
        const created = await api.createMeeting({
          orgId,
          title,
          heldAt: new Date(heldAt).toISOString(),
          transcript,
        })
        await api.analyze(created.meeting.id)
        navigate(`/orgs/${orgId}/meetings/${created.meeting.id}`)
      } else {
        if (!audioFile) {
          setError('음성 파일을 선택해 주세요.')
          setSubmitting(false)
          return
        }
        if (!consentConfirmed) {
          setError('참석자 전원의 음성 녹음 및 텍스트 변환 동의가 필요합니다.')
          setSubmitting(false)
          return
        }

        // 1. Presigned Upload URL 발급
        const uploadInfo = await api.requestAudioUploadUrl({
          orgId,
          title,
          heldAt: new Date(heldAt).toISOString(),
          filename: audioFile.name,
          fileSizeBytes: audioFile.size,
        })

        // 2. 스토리지(NCP Object Storage) 직접 업로드
        await api.uploadAudioToStorage(uploadInfo.presignedUploadUrl, audioFile)

        // 3. 비동기 전사(CLOVA Speech) 작업 트리거
        await api.triggerTranscribe(uploadInfo.meetingId)

        navigate(`/orgs/${orgId}/meetings/${uploadInfo.meetingId}`)
      }
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
          <div
            className={`source-option ${mode === 'text' ? 'chosen' : ''}`}
            onClick={() => { setMode('text'); setError(null); }}
            style={{ cursor: 'pointer' }}
          >
            <FlowIcon kind="meeting" />
            <strong>회의록으로 시작</strong>
            <span>텍스트 입력 · TXT · PDF</span>
          </div>

          <div
            className={`source-option ${mode === 'audio' ? 'chosen' : ''}`}
            onClick={() => { setMode('audio'); setError(null); }}
            style={{ cursor: 'pointer' }}
          >
            <FlowIcon kind="audio" />
            <strong>녹음으로 시작</strong>
            <span>음성 파일 업로드 · CLOVA Speech 전사</span>
          </div>
        </div>

        <p className="muted small">
          {mode === 'text'
            ? '회의록을 입력하거나 파일로 불러오세요. 한 줄에 발언 하나씩 적으면 화자가 분리됩니다.'
            : '음성 파일을 올리면 CLOVA Speech AI가 대화 내용을 화자별로 분리하여 자동으로 전사합니다.'}
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

          {mode === 'text' ? (
            <>
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
            </>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', margin: '0.5rem 0' }}>
              <label>
                음성 녹음 파일 (.m4a, .mp3, .wav)
                <input
                  type="file"
                  accept="audio/*,.m4a,.mp3,.wav"
                  onChange={handleAudioFileChange}
                  required
                  style={{ marginTop: '0.25rem' }}
                />
              </label>

              {audioFile && (
                <p className="muted small">
                  선택된 파일: <strong>{audioFile.name}</strong> ({(audioFile.size / (1024 * 1024)).toFixed(2)} MB)
                </p>
              )}

              {/* 필수 녹음 동의 체크박스 */}
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', padding: '0.5rem', background: 'var(--bg-muted, #f8f9fa)', borderRadius: '6px' }}>
                <input
                  type="checkbox"
                  checked={consentConfirmed}
                  onChange={(e) => setConsentConfirmed(e.target.checked)}
                  required
                />
                <span style={{ fontSize: '0.9rem' }}>
                  <strong>[필수]</strong> 참석자 전원의 음성 녹음 및 텍스트 변환 동의를 확인했습니다.
                </span>
              </label>
            </div>
          )}

          {notice && <p className="notice small" role="status">{notice}</p>}
          {error && <p className="error" role="alert">{error}</p>}

          <button
            type="submit"
            className="primary"
            disabled={submitting || reading || (mode === 'audio' && (!audioFile || !consentConfirmed))}
          >
            {submitting ? '처리 중…' : (mode === 'text' ? '결정·업무 후보 만들기' : '음성 올리고 전사 시작')}
          </button>
        </form>
      </main>
    </>
  )
}