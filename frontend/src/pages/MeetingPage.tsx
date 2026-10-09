import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, type Job, type MeetingDetail, type OrgDetail } from '../api'
import { formatDateTime, JOB_STATUS_LABEL, jobErrorText, MEETING_STATUS_LABEL } from '../labels'
import { canUpload, errorMessage, useLoad, useOrg } from '../useOrg'
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
      <main className="page wide">
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
  const [error, setError] = useState<string | null>(null)
  const job = liveJob && liveJob.id === latestJob?.id ? liveJob : latestJob
  const working = job != null && (job.status === 'QUEUED' || job.status === 'RUNNING')

  // 분석이 끝날 때까지 작업 상태를 2초마다 확인한다.
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
        <span className={`chip status-${meeting.status.toLowerCase()}`}>{MEETING_STATUS_LABEL[meeting.status]}</span>
        <h2>{meeting.title}</h2>
        <p className="muted small">{formatDateTime(meeting.heldAt)} · 발언 {detail.segmentCount}줄</p>
      </section>

      {working && job && (
        <div className="card notice-card" role="status">
          <span className="spinner" aria-hidden="true" />
          <div>
            <strong>AI가 회의록을 읽고 있습니다… ({JOB_STATUS_LABEL[job.status]})</strong>
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

      {canUpload(org) && (meeting.status === 'READY' || meeting.status === 'ANALYSIS_FAILED') && (
        <div className="actions-row">
          <button type="button" className="primary inline" onClick={startAnalysis} disabled={requesting}>
            {meeting.status === 'READY' ? '분석 시작' : '다시 분석'}
          </button>
          {meeting.status === 'ANALYSIS_FAILED' && (
            <Link to={`/orgs/${org.id}/meetings/new`} className="ghost">회의록 새로 올리기</Link>
          )}
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}

      {meeting.status === 'ANALYZED' && <ReviewPanel org={org} meetingId={meeting.id} />}
    </>
  )
}
