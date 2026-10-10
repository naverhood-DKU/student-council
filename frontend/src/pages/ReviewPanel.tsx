import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api, ApiError, type DecisionView, type OrgDetail, type Proposals, type Segment, type TaskView } from '../api'
import { AI_STATUS_LABEL, DECISION_STATUS_LABEL, TASK_STATUS_LABEL } from '../labels'
import { errorMessage, isOwner, useLoad } from '../useOrg'
import { AddTaskForm } from './TaskForms'

type Selection = { kind: 'decision' | 'task'; id: number; segmentIds: number[] } | null

/**
 * AI 제안 검토 화면. 왼쪽은 결정·업무 후보 카드, 오른쪽은 회의록.
 * 카드를 누르면 근거 발언이 회의록에서 강조되고 카드 안에도 원문이 보인다.
 */
export function ReviewPanel({ org, meetingId }: { org: OrgDetail; meetingId: number }) {
  const [proposals, reload] = useLoad<Proposals>(
    () => api.proposals(meetingId),
    [meetingId],
    'AI 제안을 불러오지 못했습니다.',
  )
  const [selection, setSelection] = useState<Selection>(null)

  useEffect(() => {
    const first = selection?.segmentIds[0]
    if (first == null || !window.matchMedia('(min-width: 900px)').matches) return
    document.getElementById(`seg-${first}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [selection])

  if (proposals.status === 'loading') return <p className="page-message">AI 제안을 불러오는 중…</p>
  if (proposals.status === 'error') return <p className="error">{proposals.message}</p>
  const data = proposals.data
  const highlighted = new Set(selection?.segmentIds ?? [])
  const pending = data.decisions.filter((d) => d.status === 'PROPOSED').length

  return (
    <>
      <p className="muted small">
        AI가 만든 <strong>후보</strong>입니다. 근거 발언을 확인하고 고친 뒤 승인해야 확정됩니다.
        {pending > 0 ? ` 검토할 후보 ${pending}건.` : ' 모든 후보를 검토했습니다.'}{' '}
        <Link to={`/orgs/${org.id}/board`}>업무보드 보기</Link>
      </p>
      <div className="review">
        <div className="candidates">
          {data.decisions.length === 0 && <p className="empty card">AI가 찾은 결정이 없습니다.</p>}
          {data.decisions.map((decision) => (
            <DecisionCard
              key={`${decision.id}-${decision.version}`}
              decision={decision}
              departments={data.departments}
              chair={isOwner(org)}
              selection={selection}
              onSelect={setSelection}
              onChanged={reload}
              deliveryLink={`/orgs/${org.id}/decisions/${decision.id}/deliveries`}
              expenseLink={expenseLinkFor(org, decision)}
            />
          ))}
        </div>
        <aside className="transcript card" aria-label="회의록">
          <h3>회의록</h3>
          <ol className="segments">
            {data.segments.map((s) => (
              <li key={s.id} id={`seg-${s.id}`} className={highlighted.has(s.id) ? 'highlight' : undefined}>
                <SegmentLine segment={s} />
              </li>
            ))}
          </ol>
        </aside>
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

/** 승인된 결정에 지출 언급이 있으면, 값을 미리 채운 지출 절차 확인 주소를 만든다. 요청은 회장·부장급만 할 수 있다. */
function expenseLinkFor(org: OrgDetail, decision: DecisionView): string | null {
  const proposal = decision.expenseProposal
  if (decision.status !== 'APPROVED' || !proposal || (org.myRole !== 'OWNER' && org.myRole !== 'EDITOR')) return null
  const query = new URLSearchParams({ decisionId: String(decision.id), item: proposal.item })
  if (proposal.amount != null) query.set('amount', String(proposal.amount))
  if (proposal.fundSource) query.set('fundSource', proposal.fundSource)
  return `/orgs/${org.id}/expense?${query.toString()}`
}

interface CardProps {
  decision: DecisionView
  departments: { id: number; name: string }[]
  chair: boolean
  selection: Selection
  onSelect: (s: Selection) => void
  onChanged: () => void
  /** 승인된 결정의 업무 요청 화면 주소 */
  deliveryLink: string | null
  /** 승인된 결정의 지출 절차 확인 화면 주소(지출 언급이 있을 때만) */
  expenseLink: string | null
}

function DecisionCard({ decision, departments, chair, selection, onSelect, onChanged, deliveryLink, expenseLink }: CardProps) {
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

  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
    } catch (e) {
      setError(conflictAware(e, onChanged, fallback))
    } finally {
      setBusy(false)
    }
  }

  function approve() {
    if (needsCheck && !window.confirm('근거 확인이 필요한 후보가 있습니다. 근거를 확인했고 승인할까요?')) return
    run(() => api.approve(decision), '승인하지 못했습니다.')
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
      {decision.tasks.map((task) => (
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
      {expenseLink && <Link to={expenseLink} className="ghost deliver-link">지출 절차 확인</Link>}
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
            승인
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
        </div>
      )}
    </div>
  )
}
