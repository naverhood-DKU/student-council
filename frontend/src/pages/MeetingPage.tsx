import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, type Job, type MeetingDetail, type OrgDetail, type TranscribeStatusResponse } from '../api'
import { formatDateTime, JOB_STATUS_LABEL, jobErrorText, MEETING_STATUS_LABEL } from '../labels'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { ReviewPanel } from './ReviewPanel'
import { TopBar } from './TopBar'

const POLL_MS = 2000

export function MeetingPage() {
  const params = useParams()
  const orgId = Number(params.orgId)
  const meetingId = Number(params.meetingId)
  const [org] = useOrg(orgId)
  const [detail, reloadDetail] = useLoad<MeetingDetail>(
    () => api.meeting(meetingId),
    [meetingId],
    '회의를 불러오지 못했습니다.',
  )

  return (
    <>
      <TopBar />
      <main className="page wide meeting-page">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        {(org.status === 'loading' || detail.status === 'loading') && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {detail.status === 'error' && <p className="error" role="alert">{detail.message}</p>}
        {org.status === 'ready' && detail.status === 'ready' && (
          <Meeting org={org.data} detail={detail.data} reload={reloadDetail} />
        )}
      </main>
    </>
  )
}

function Meeting({ org, detail, reload }: { org: OrgDetail; detail: MeetingDetail; reload: () => void }) {
  const { meeting, latestJob } = detail
  const [liveJob, setLiveJob] = useState<Job | null>(null)
  const [requesting, setRequesting] = useState(false)
  const [reviewComplete, setReviewComplete] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [transcribeStatus, setTranscribeStatus] = useState<TranscribeStatusResponse | null>(null)

  const job = liveJob && liveJob.id === latestJob?.id ? liveJob : latestJob
  const working = job != null && (job.status === 'QUEUED' || job.status === 'RUNNING')
  const isAudioMeeting = meeting.sourceType === 'AUDIO'
  const isTranscribing = transcribeStatus?.status === 'PENDING' || transcribeStatus?.status === 'PROCESSING'

  // 1. 음성 회의인 경우 CLOVA Speech 전사 진행 상태 확인 (3초 주기)
  useEffect(() => {
    if (!isAudioMeeting || meeting.status === 'ANALYZED' || meeting.status === 'ANALYZING') return

    let timer: ReturnType<typeof setTimeout>
    async function checkAudioStatus() {
      try {
        const res = await api.getTranscribeStatus(meeting.id)
        setTranscribeStatus(res)
        if (res.status === 'PENDING' || res.status === 'PROCESSING') {
          timer = setTimeout(checkAudioStatus, 3000)
        } else if (res.status === 'COMPLETED') {
          reload()
        }
      } catch {
        // 일시적 오류는 무시
      }
    }

    checkAudioStatus()
    return () => clearTimeout(timer)
  }, [isAudioMeeting, meeting.id, meeting.status, reload])

  // 2. 결정·업무 AI 분석 상태 확인 (2초 주기)
  useEffect(() => {
    if (!latestJob || (latestJob.status !== 'QUEUED' && latestJob.status !== 'RUNNING')) return
    const timer = setInterval(async () => {
      try {
        const next = await api.job(latestJob.id)
        setLiveJob(next)
        if (next.status !== 'QUEUED' && next.status !== 'RUNNING') reload()
      } catch {
        // 일시적인 네트워크 오류는 다음 주기에 다시 확인한다.
      }
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [latestJob, reload])

  async function startAnalysis() {
    setRequesting(true)
    setError(null)
    try {
      await api.analyze(meeting.id)
      reload()
    } catch (e) {
      setError(errorMessage(e, '분석을 시작하지 못했습니다.'))
    } finally {
      setRequesting(false)
    }
  }
  return (
    <>
      <section className="org-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
          <span className="chip subtle">{isAudioMeeting ? '🎙️ 음성 녹음' : '📄 텍스트 회의록'}</span>
          <span className={`chip status-${meeting.status.toLowerCase()}`}>
            {reviewComplete ? '검토 완료' : MEETING_STATUS_LABEL[meeting.status]}
          </span>
        </div>
        <h2>{meeting.title}</h2>
        <p className="meeting-subtitle">회의에서 나온 결정이 각 부서의 할 일로 이어집니다.</p>
        <p className="muted small">
          {formatDateTime(meeting.heldAt)}
          {detail.segmentCount > 0 ? ` · 발언 ${detail.segmentCount}줄` : isTranscribing ? ' · 음성 텍스트 변환 중…' : ''}
        </p>
      </section>

      <ol className="meeting-progress" aria-label="회의 분석 진행 단계">
        <li className="complete">
          <span>01</span>
          <strong>{isAudioMeeting ? '음성 전사' : '회의록 등록'}</strong>
          <small>{isTranscribing ? '텍스트 변환 중' : '원문 준비 완료'}</small>
        </li>
        <li className={meeting.status === 'ANALYZED' ? 'complete' : working ? 'current' : ''}>
          <span>02</span>
          <strong>결정·업무 연결</strong>
          <small>{working ? 'AI가 분석 중' : meeting.status === 'ANALYZED' ? '분석 완료' : '분석 대기'}</small>
        </li>
        <li className={reviewComplete ? 'complete' : meeting.status === 'ANALYZED' ? 'current' : ''}>
          <span>03</span>
          <strong>검토 후 업무 확정</strong>
          <small>{reviewComplete ? '검토 완료 · 업무보드 연결' : '근거 확인 · 승인'}</small>
        </li>
      </ol>

      {/* CLOVA Speech 음성 전사 진행 중 안내 */}
      {isAudioMeeting && isTranscribing && (
        <div className="card notice-card" role="status" style={{ marginBottom: '1rem' }}>
          <span className="spinner" aria-hidden="true" />
          <div>
            <strong>CLOVA Speech AI가 음성을 텍스트로 변환하고 화자를 분리하고 있습니다…</strong>
            <p className="muted small">
              파일 길이에 따라 수십 초에서 수 분이 걸릴 수 있습니다. 완료되면 자동으로 분석 준비가 완료됩니다.
            </p>
          </div>
        </div>
      )}

      {/* CLOVA Speech 음성 전사 실패 안내 */}
      {transcribeStatus?.status === 'FAILED' && (
        <div className="card warn-card" role="alert" style={{ marginBottom: '1rem' }}>
          <strong>음성 텍스트 변환(전사)에 실패했습니다.</strong>
          <p>{transcribeStatus.error || '음성 파일을 변환하는 중 오류가 발생했습니다.'}</p>
        </div>
      )}

      {/* AI 결정·업무 분석 진행 중 안내 */}
      {working && job && (
        <div className="card notice-card" role="status">
          <span className="spinner" aria-hidden="true" />
          <div>
            <strong>AI가 결정사항과 부서별 업무를 연결하고 있습니다… ({JOB_STATUS_LABEL[job.status]})</strong>
            <p className="muted small">
              보통 몇십 초 걸립니다. 이 화면을 닫아도 분석은 계속됩니다.
              {job.lastError && ` 일시 오류로 다시 시도하는 중입니다(${job.attempts}/3회).`}
            </p>
          </div>
        </div>
      )}

      {meeting.status === 'ANALYSIS_FAILED' && (
        <div className="card warn-card" role="alert">
          <strong>분석하지 못했습니다.</strong>
          <p>{jobErrorText(job?.lastError ?? null)}</p>
          <p className="muted small">
            다시 분석하거나, 회의록을 고쳐 새로 올릴 수 있습니다. 회의 내용은 그대로 남아 있습니다.
          </p>
        </div>
      )}

      {isOwner(org) && (meeting.status === 'READY' || meeting.status === 'ANALYSIS_FAILED') && (
        <div className="actions-row">
          <button type="button" className="primary inline" onClick={startAnalysis} disabled={requesting || isTranscribing}>
            {meeting.status === 'READY' ? '결정·업무 분석 시작' : '다시 분석'}
          </button>
          {meeting.status === 'ANALYSIS_FAILED' && (
            <Link to={`/orgs/${org.id}/meetings/new`} className="ghost">회의록 새로 올리기</Link>
          )}
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}

      {meeting.status === 'ANALYZED' && (
        <ReviewPanel org={org} meetingId={meeting.id} onReviewComplete={setReviewComplete} />
      )}
    </>
  )
}