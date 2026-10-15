import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { RuleCheckBadge } from './RuleCheckBadge'
import { api, ApiError, type DecisionView, type OrgDetail, type Proposals, type Segment, type TaskView } from '../api'
import { AI_STATUS_LABEL, confirmDeleteTask, DECISION_STATUS_LABEL, TASK_STATUS_LABEL } from '../labels'
import { errorMessage, isOwner, useLoad } from '../useOrg'
import { AddTaskForm } from './TaskForms'
import { MeetingFlow } from './MeetingFlow'
import { departmentBranches } from '../meetingBranches'

type Selection = { kind: 'decision' | 'task'; id: number; segmentIds: number[] } | null

/**
 * 회의록 → 결정 → 부서별 업무의 가지를 보여 준다.
 * 선택한 가지의 검토·승인 편집기와 근거 회의록은 아래에서 함께 열린다.
 */
export function ReviewPanel({ org, meetingId, onReviewComplete }: { org: OrgDetail; meetingId: number; onReviewComplete?: (complete: boolean) => void }) {
  const [proposals, reload] = useLoad<Proposals>(
    () => api.proposals(meetingId),
    [meetingId],
    'AI 제안을 불러오지 못했습니다.',
  )
  const [selection, setSelection] = useState<Selection>(null)
  const [transcriptOpen, setTranscriptOpen] = useState(false)
  const [slide, setSlide] = useState<'next' | 'prev' | null>(null)
  // 화면 그리는 중에 채워 두고, 키보드 이벤트에서 꺼내 쓴다
  const navRef = useRef<{ prev: (() => void) | null; next: (() => void) | null }>({ prev: null, next: null })

  useEffect(() => {
    if (proposals.status === 'ready') {
      onReviewComplete?.(proposals.data.decisions.length > 0 && proposals.data.decisions.every(d => d.status === 'APPROVED' || d.status === 'CANCELLED'))
    }
  }, [proposals, onReviewComplete])

  const modalOpen = selection != null || transcriptOpen
  const closeModal = () => { setSelection(null); setTranscriptOpen(false) }

  // 모달이 열려 있는 동안: Esc 로 닫기, 뒤 화면 스크롤 잠금, 근거 발언을 모달 안에서 가운데로 보여 주기
  useEffect(() => {
    if (!modalOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setSelection(null); setTranscriptOpen(false); return }
      const el = e.target as HTMLElement | null
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT')) return
      if (e.key === 'ArrowLeft') navRef.current.prev?.()
      if (e.key === 'ArrowRight') navRef.current.next?.()
    }
    window.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [modalOpen])

  useEffect(() => {
    const first = selection?.segmentIds[0]
    if (first == null) return
    document.getElementById(`seg-${first}`)?.scrollIntoView({ block: 'center', behavior: 'auto' })
  }, [selection])

  if (proposals.status === 'loading') return <p className="page-message">AI 제안을 불러오는 중…</p>
  if (proposals.status === 'error') return <p className="error">{proposals.message}</p>
  const data = proposals.data
  const highlighted = new Set(selection?.segmentIds ?? [])
  const pending = data.decisions.filter((d) => d.status === 'PROPOSED').length

  const selectedDecision = data.decisions.find(d => selection?.kind === 'decision'
    ? d.id === selection.id : d.tasks.some(t => t.id === selection?.id))

  // 흐름도에 보이는 순서대로 검토할 카드를 한 줄로 늘어놓는다(업무 없는 결정은 결정 하나가 한 칸)
  const items = data.decisions.flatMap(d => {
    const pending = d.status === 'PROPOSED' || d.status === 'ON_HOLD'
    const tasks = departmentBranches(d.tasks).flatMap(b => b.tasks)
    const entries: NonNullable<Selection>[] = tasks.length > 0
      ? tasks.map(t => ({ kind: 'task', id: t.id, segmentIds: t.sourceSegmentIds }))
      : [{ kind: 'decision', id: d.id, segmentIds: d.sourceSegmentIds }]
    return entries.map(entry => ({ entry, decisionId: d.id, pending }))
  })
  const currentIndex = !selection ? -1 : items.findIndex(it => selection.kind === 'task'
    ? it.entry.kind === 'task' && it.entry.id === selection.id
    : it.decisionId === selection.id)
  const goTo = (i: number, dir: 'next' | 'prev') => {
    if (i < 0 || i >= items.length) return
    setSlide(dir)
    setSelection(items[i].entry)
  }
  // 방금 승인한 결정의 나머지 카드는 건너뛰고, 뒤에 남은 승인 전 카드로 간다
  const nextPendingAfter = (decisionId: number) =>
    items.findIndex((it, i) => i > currentIndex && it.pending && it.decisionId !== decisionId)
  const afterApproved = (decisionId: number) => {
    const next = nextPendingAfter(decisionId)
    if (next >= 0) goTo(next, 'next')
    else closeModal()
  }
  navRef.current = {
    prev: currentIndex > 0 ? () => goTo(currentIndex - 1, 'prev') : null,
    next: currentIndex >= 0 && currentIndex < items.length - 1 ? () => goTo(currentIndex + 1, 'next') : null,
  }

  return (
    <>
      <MeetingFlow data={data} selection={selection} onSelect={setSelection}
        onOpenTranscript={() => {
          setSelection(null)
          setTranscriptOpen(true)
        }} />
      <div className="review-summary">
        <p>{pending > 0 ? `${pending}개의 결정을 검토하면 업무가 확정됩니다.` : '결정 검토를 마쳤습니다. 업무보드에서 진행 상황을 확인하세요.'}</p>
        <Link to={`/orgs/${org.id}/board`} className="ghost">업무보드로 이동 →</Link>
      </div>
      <div className="review-modal-backdrop" hidden={!modalOpen} onMouseDown={(e) => { if (e.target === e.currentTarget) closeModal() }}>
      <section id="meeting-inspector" className="meeting-inspector review-modal" role="dialog" aria-modal="true"
               aria-label="선택한 결정과 근거 검토">
        <header className="inspector-heading"><h3>{selectedDecision ? '결정 · 업무 검토' : '회의록 원문'}</h3>
          {selectedDecision && currentIndex >= 0 && items.length > 1 && (
            <div className="review-nav" aria-label="업무 카드 이동">
              <button type="button" className="ghost" disabled={!navRef.current.prev} onClick={() => navRef.current.prev?.()} aria-label="이전 카드">‹ 이전</button>
              <span className="muted small">{currentIndex + 1} / {items.length}</span>
              <button type="button" className="ghost" disabled={!navRef.current.next} onClick={() => navRef.current.next?.()} aria-label="다음 카드">다음 ›</button>
            </div>
          )}
          <button type="button" className="ghost" onClick={closeModal}>닫기 ✕</button>
        </header>
        <div className="review-modal-body">
        <div key={`${selection?.kind}-${selection?.id}`} className={slide ? `slide-in-${slide}` : undefined}>
        <div className={selectedDecision ? 'review' : 'transcript-only'}>
          <div className="candidates" hidden={!selectedDecision}>
            {data.decisions.map(decision => <div key={`${decision.id}-${decision.version}`} hidden={decision.id !== selectedDecision?.id}>
              <DecisionCard decision={decision} departments={data.departments} chair={isOwner(org)}
                selection={selection} onSelect={setSelection} onChanged={reload} onApproved={() => afterApproved(decision.id)}
                approveLabel={nextPendingAfter(decision.id) >= 0 ? '승인하고 다음 카드' : '승인'}
                onlyTaskId={selection?.kind === 'task' && decision.id === selectedDecision?.id ? selection.id : null}
                deliveryLink={`/orgs/${org.id}/decisions/${decision.id}/deliveries`} />
            </div>)}
          </div>
          <aside className="transcript card" aria-label="회의록">
            <h3>{selection ? '근거가 된 회의록' : '회의록 원문'}</h3><p className="muted small">{selection ? '선택한 결정·업무의 근거 발언을 강조했습니다.' : '회의에서 나눈 발언을 원문 그대로 확인하세요.'}</p>
            <ol className="segments">{data.segments.map(s => <li key={s.id} id={`seg-${s.id}`} className={highlighted.has(s.id) ? 'highlight' : undefined}><SegmentLine segment={s} /></li>)}</ol>
          </aside>
        </div>
        </div>
        </div>
      </section>
      </div>
    </>
  )
}

function SegmentLine({ segment }: { segment: Segment }) {
  return (
    <>
      <span className="seg-label">{segment.label}</span>
      {segment.speaker && <strong>{segment.speaker}: </strong>}
      {segment.text}
    </>
  )
}

function Evidence({ segments }: { segments: Segment[] }) {
  if (segments.length === 0) return <p className="warn-text small">근거 발언이 없습니다.</p>
  return (
    <ul className="evidence">
      {segments.map((s) => (
        <li key={s.id}><SegmentLine segment={s} /></li>
      ))}
    </ul>
  )
}

function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) return null
  return (
    <ul className="notes">
      {notes.map((n) => <li key={n}>{n}</li>)}
    </ul>
  )
}

function conflictAware(e: unknown, onChanged: () => void, fallback: string): string {
  if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') {
    onChanged()
    return '다른 사람이 먼저 고쳤습니다. 최신 내용을 다시 불러왔습니다.'
  }
  return errorMessage(e, fallback)
}

interface CardProps {
  decision: DecisionView
  departments: { id: number; name: string }[]
  chair: boolean
  selection: Selection
  onSelect: (s: Selection) => void
  onChanged: () => void
  /** 결정을 승인한 직후(모달을 닫는다) */
  onApproved: () => void
  /** 업무 카드를 눌러 열었을 때 그 업무만 보여 준다(null 이면 결정의 업무 전부) */
  onlyTaskId: number | null
  /** 승인 버튼 글자 */
  approveLabel: string
  /** 승인된 결정의 업무 요청 화면 주소 */
  deliveryLink: string | null
}

function DecisionCard({ decision, departments, chair, selection, onSelect, onChanged, onApproved, onlyTaskId, approveLabel, deliveryLink }: CardProps) {
  const editable = chair && (decision.status === 'PROPOSED' || decision.status === 'ON_HOLD')
  const canAddTask = chair && (editable || decision.status === 'APPROVED')
  const [summary, setSummary] = useState(decision.summary)
  const [busy, setBusy] = useState(false)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const selected = selection?.kind === 'decision' && selection.id === decision.id
  const needsCheck =
    decision.evidenceStatus === 'NEEDS_CHECK' ||
    decision.tasks.some((t) => t.status !== 'CANCELLED' && t.evidenceStatus === 'NEEDS_CHECK')

  async function run(action: () => Promise<unknown>, fallback: string, afterOk?: () => void) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
      afterOk?.()
    } catch (e) {
      setError(conflictAware(e, onChanged, fallback))
    } finally {
      setBusy(false)
    }
  }

  function approve() {
    if (needsCheck && !window.confirm('근거 확인이 필요한 후보가 있습니다. 근거를 확인했고 승인할까요?')) return
    run(() => api.approve(decision), '승인하지 못했습니다.', onApproved)
  }

  return (
    <article className={`card decision${selected ? ' selected' : ''}`}>
      <button
        type="button"
        className="card-head"
        onClick={() => onSelect(selected ? null : { kind: 'decision', id: decision.id, segmentIds: decision.sourceSegmentIds })}
        aria-expanded={selected}
      >
        <span className="chip">{decision.agenda}</span>
        <span className={`chip status-${decision.status.toLowerCase()}`}>{DECISION_STATUS_LABEL[decision.status]}</span>
        {decision.aiStatus && <span className="chip subtle">{AI_STATUS_LABEL[decision.aiStatus]}</span>}
        {decision.evidenceStatus === 'NEEDS_CHECK' && <span className="chip warn">근거 확인 필요</span>}
      </button>

      {editable ? (
        <label className="compact">
          <span className="sr-only">결정 요약</span>
          <textarea value={summary} onChange={(e) => setSummary(e.target.value)} rows={2} maxLength={2000} />
        </label>
      ) : (
        <p className="summary">{decision.summary}</p>
      )}
      <Notes notes={decision.validationNotes} />
      {selected && <Evidence segments={decision.evidence} />}

      {decision.expenseProposal && (
        <p className="muted small">
          지출 언급: {decision.expenseProposal.item}
          {decision.expenseProposal.amount != null && ` · ${decision.expenseProposal.amount.toLocaleString('ko-KR')}원`}
          {decision.expenseProposal.fundSource && ` · 재원 ${decision.expenseProposal.fundSource}`}
        </p>
      )}

      {decision.tasks.length > 0 && <h4>업무</h4>}
      {onlyTaskId != null && decision.tasks.length > 1 && (
        <p className="muted small">
          이 결정에는 업무 {decision.tasks.length - 1}개가 더 있습니다.
          {editable && ' 승인하면 함께 확정됩니다.'}{' '}
          <button type="button" className="link-button"
                  onClick={() => onSelect({ kind: 'decision', id: decision.id, segmentIds: decision.sourceSegmentIds })}>
            결정 전체 보기
          </button>
        </p>
      )}
      {decision.tasks.filter((task) => onlyTaskId == null || task.id === onlyTaskId).map((task) => (
        <TaskRow
          key={`${task.id}-${task.version}`}
          task={task}
          departments={departments}
          editable={editable}
          selected={selection?.kind === 'task' && selection.id === task.id}
          onSelect={onSelect}
          onChanged={onChanged}
        />
      ))}

      {adding && (
        <AddTaskForm
          departments={departments}
          decisionId={decision.id}
          onDone={() => {
            setAdding(false)
            onChanged()
          }}
          onCancel={() => setAdding(false)}
        />
      )}
      {canAddTask && !adding && (
        <button type="button" className="link-button add-task" onClick={() => setAdding(true)}>+ 업무 직접 추가</button>
      )}

      {error && <p className="error" role="alert">{error}</p>}
      {decision.status === 'APPROVED' && decision.approvedByName && (
        <p className="muted small">{decision.approvedByName}님이 승인했습니다.</p>
      )}
      {decision.status === 'APPROVED' && deliveryLink && (
        <Link to={deliveryLink} className="ghost deliver-link">다른 그룹에 업무 요청 · 요청 현황</Link>
      )}
      {editable && (
        <div className="actions-row">
          {summary.trim() !== decision.summary && (
            <button type="button" className="ghost" disabled={busy || !summary.trim()}
                    onClick={() => run(() => api.editDecision(decision, { summary }), '저장하지 못했습니다.')}>
              요약 저장
            </button>
          )}
          <button type="button" className="primary inline" disabled={busy || summary.trim() !== decision.summary}
                  onClick={approve}>
            {approveLabel}
          </button>
        </div>
      )}
    </article>
  )
}

interface TaskRowProps {
  task: TaskView
  departments: { id: number; name: string }[]
  editable: boolean
  selected: boolean
  onSelect: (s: Selection) => void
  onChanged: () => void
}

function TaskRow({ task, departments, editable: decisionEditable, selected, onSelect, onChanged }: TaskRowProps) {
  const cancelled = task.status === 'CANCELLED'
  const editable = decisionEditable && !cancelled
  const [title, setTitle] = useState(task.title)
  const [departmentId, setDepartmentId] = useState<number | null>(task.departmentId)
  const [dueDate, setDueDate] = useState(task.dueDate ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const dirty = title.trim() !== task.title || departmentId !== task.departmentId || (dueDate || null) !== task.dueDate

  async function run(change: Parameters<typeof api.updateTask>[1], fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await api.updateTask(task, change)
      onChanged()
    } catch (e) {
      setError(conflictAware(e, onChanged, fallback))
    } finally {
      setBusy(false)
    }
  }

  const save = () => run({ title: title.trim(), departmentId, dueDate: dueDate || null }, '업무를 저장하지 못했습니다.')

  function exclude() {
    if (window.confirm(`"${task.title}"을(를) 후보에서 뺄까요? 승인해도 업무보드에 올라가지 않습니다.`)) {
      run({ status: 'CANCELLED' }, '후보에서 빼지 못했습니다.')
    }
  }

  /** 업무 완전 삭제(회장만). 후보에서 빼기와 달리 기록도 남지 않는다. */
  async function remove() {
    if (!confirmDeleteTask(task.title)) return
    setBusy(true)
    setError(null)
    try {
      await api.deleteTask(task.id)
      onChanged()
    } catch (e) {
      setError(errorMessage(e, '업무를 지우지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`task${selected ? ' selected' : ''}${cancelled ? ' cancelled-task' : ''}`}>
      <button
        type="button"
        className="task-head"
        onClick={() => onSelect(selected ? null : { kind: 'task', id: task.id, segmentIds: task.sourceSegmentIds })}
        aria-expanded={selected}
      >
        <span>{task.title}</span>
        <span className="chip subtle">{cancelled ? '후보에서 뺌' : TASK_STATUS_LABEL[task.status]}</span>
        {task.origin === 'MANUAL' && <span className="chip subtle">직접 추가</span>}
        {!cancelled && task.evidenceStatus === 'NEEDS_CHECK' && <span className="chip warn">근거 확인 필요</span>}
      </button>

      {editable ? (
        <div className="task-fields">
          <label className="compact">
            할 일
            <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={300} />
          </label>
          <label className="compact">
            담당 부서
            <select
              value={departmentId ?? ''}
              onChange={(e) => setDepartmentId(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">미정</option>
              {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </label>
          <label className="compact">
            기한
            <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
          </label>
        </div>
      ) : (
        <p className="muted small">
          담당 {task.departmentName ?? '미정'} · 기한 {task.dueDate ?? '미정'}
        </p>
      )}

      {task.departmentByAi && departmentId === task.departmentId && (
        <p className="small ai-pick">
          <span className="chip warn inline-chip">AI 지정</span>
          {' '}회의에서 담당을 말하지 않아 AI가 업무 내용으로 고른 부서입니다. 맞지 않으면 바꿔 주세요.
          {task.departmentReason && <span className="muted block">이유: {task.departmentReason}</span>}
        </p>
      )}
      {(task.ownerDepartmentText || task.suggestedDepartmentName) && (
        <p className="muted small">
          {task.ownerDepartmentText && `회의에서 말한 담당: ${task.ownerDepartmentText}. `}
          {task.suggestedDepartmentName && task.departmentId == null && (
            <>
              추천(확정 아님): {task.suggestedDepartmentName}
              {editable && departmentId !== task.suggestedDepartmentId && (
                <button type="button" className="link-button" onClick={() => setDepartmentId(task.suggestedDepartmentId)}>
                  적용
                </button>
              )}
            </>
          )}
        </p>
      )}
      <Notes notes={task.validationNotes} />
      {!cancelled && <RuleCheckBadge taskId={task.id} initial={task.ruleCheck} />}
      {selected && <Evidence segments={task.evidence} />}
      {error && <p className="error" role="alert">{error}</p>}
      {editable && (
        <div className="actions-row">
          {dirty && (
            <button type="button" className="ghost" disabled={busy || !title.trim()} onClick={save}>업무 저장</button>
          )}
          {task.status === 'PROPOSED' && (
            <button type="button" className="link-button" disabled={busy} onClick={exclude}>후보에서 빼기</button>
          )}
          <button type="button" className="link-button danger" disabled={busy} onClick={remove}>삭제</button>
        </div>
      )}
    </div>
  )
}
