import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, ApiError, type BoardTask, type DecisionOption, type OrgDetail, type TaskPatch, type TaskStatus } from '../api'
import { isOverdue, TASK_STATUS_LABEL } from '../labels'
import { errorMessage, isChair, useLoad, useOrg } from '../useOrg'
import { AddTaskForm, canClearDepartment } from './TaskForms'
import { TopBar } from './TopBar'

const COLUMNS: { key: string; label: string; statuses: TaskStatus[] }[] = [
  { key: 'todo', label: '할 일', statuses: ['UNASSIGNED', 'ASSIGNED'] },
  { key: 'doing', label: '진행', statuses: ['IN_PROGRESS'] },
  { key: 'submitted', label: '결과 제출', statuses: ['SUBMITTED'] },
  { key: 'done', label: '완료', statuses: ['DONE'] },
]

export function BoardPage() {
  const orgId = Number(useParams().orgId)
  const [org] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page wide">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <Board org={org.data} />}
      </main>
    </>
  )
}

function Board({ org }: { org: OrgDetail }) {
  const [tasks, reload] = useLoad<BoardTask[]>(() => api.board(org.id), [org.id], '업무를 불러오지 못했습니다.')
  const [filter, setFilter] = useState<string>(
    org.myDepartmentIds.length === 1 ? String(org.myDepartmentIds[0]) : 'all',
  )
  const [overdueOnly, setOverdueOnly] = useState(false)
  const [adding, setAdding] = useState<DecisionOption[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const chair = isChair(org)

  async function update(task: BoardTask, change: TaskPatch): Promise<boolean> {
    setError(null)
    try {
      await api.updateTask(task, change)
      reload()
      return true
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === 'VERSION_CONFLICT'
          ? '다른 사람이 먼저 바꿨습니다. 최신 상태를 다시 불러왔습니다.'
          : errorMessage(e, '업무를 바꾸지 못했습니다.'),
      )
      reload()
      return false
    }
  }

  async function openAdd() {
    setError(null)
    try {
      setAdding(await api.approvedDecisions(org.id))
    } catch (e) {
      setError(errorMessage(e, '결정 목록을 불러오지 못했습니다.'))
    }
  }

  const all = tasks.status === 'ready' ? tasks.data : []
  const visible = all.filter(
    (t) =>
      (filter === 'all' || String(t.departmentId) === filter) &&
      (!overdueOnly || isOverdue(t.dueDate, t.status)),
  )
  const cancelled = visible.filter((t) => t.status === 'CANCELLED')
  const overdueCount = all.filter((t) => isOverdue(t.dueDate, t.status)).length

  return (
    <>
      <div className="actions-row between">
        <h2>부서 업무보드</h2>
        <div className="actions-row tight">
          <label className="compact inline-label">
            부서
            <select value={filter} onChange={(e) => setFilter(e.target.value)}>
              <option value="all">전체</option>
              {org.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </label>
          <label className="compact inline-label check">
            <input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} />
            기한 지남만{overdueCount > 0 && ` (${overdueCount})`}
          </label>
          {chair && adding == null && (
            <button type="button" className="primary inline" onClick={openAdd}>업무 추가</button>
          )}
        </div>
      </div>
      <p className="muted small">
        회의 책임자가 승인한 업무만 보입니다.{chair && ' 카드의 "편집"으로 내용·담당·기한을 바꾸거나 업무를 취소할 수 있습니다.'}
      </p>
      {adding != null && (
        <AddTaskForm
          departments={org.departments}
          decisions={adding}
          onDone={() => {
            setAdding(null)
            reload()
          }}
          onCancel={() => setAdding(null)}
        />
      )}
      {error && <p className="error" role="alert">{error}</p>}
      {tasks.status === 'loading' && <p className="muted">불러오는 중…</p>}
      {tasks.status === 'error' && <p className="error">{tasks.message}</p>}
      {tasks.status === 'ready' && (
        <>
          <div className="board">
            {COLUMNS.map((column) => {
              const items = visible.filter((t) => column.statuses.includes(t.status))
              return (
                <section key={column.key} className="board-column">
                  <h3>{column.label} <span className="muted small">{items.length}</span></h3>
                  {items.length === 0 && <p className="empty small">없음</p>}
                  {items.map((task) => (
                    <TaskCard key={`${task.id}-${task.version}`} task={task} org={org} chair={chair} onUpdate={update} />
                  ))}
                </section>
              )
            })}
          </div>
          {cancelled.length > 0 && (
            <details className="cancelled">
              <summary>취소된 업무 {cancelled.length}건</summary>
              <ul className="row-list">
                {cancelled.map((t) => (
                  <li key={t.id} className="muted small">
                    {t.title} · {t.departmentName ?? '담당 미정'} · {t.meetingTitle}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
    </>
  )
}

interface TaskCardProps {
  task: BoardTask
  org: OrgDetail
  chair: boolean
  onUpdate: (task: BoardTask, change: TaskPatch) => Promise<boolean>
}

function TaskCard({ task, org, chair, onUpdate }: TaskCardProps) {
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(false)
  const mine = task.departmentId != null && org.myDepartmentIds.includes(task.departmentId)
  const canWork = chair || mine
  const overdue = isOverdue(task.dueDate, task.status)
  const finished = task.status === 'DONE' || task.status === 'CANCELLED'

  async function act(change: TaskPatch) {
    setBusy(true)
    const ok = await onUpdate(task, change)
    if (!ok) setBusy(false)
  }

  function cancelTask() {
    if (window.confirm(`"${task.title}" 업무를 취소할까요? 취소한 업무는 되돌릴 수 없습니다.`)) {
      act({ status: 'CANCELLED' })
    }
  }

  return (
    <article className={`card board-card${overdue ? ' overdue' : ''}`}>
      <div className="card-title-row">
        <strong>{task.title}</strong>
        {chair && !finished && !editing && (
          <button type="button" className="link-button" onClick={() => setEditing(true)}>편집</button>
        )}
      </div>
      <p className="muted small">
        {task.departmentName ?? '담당 미정'} · 기한 {task.dueDate ?? '미정'}
        {overdue && <span className="chip warn inline-chip">기한 지남</span>}
        {task.origin === 'MANUAL' && <span className="chip subtle inline-chip">직접 추가</span>}
      </p>
      <p className="small clamp">
        <Link to={`/orgs/${org.id}/meetings/${task.meetingId}`}>{task.meetingTitle}</Link>: {task.decisionSummary}
      </p>

      {editing ? (
        <EditTask task={task} departments={org.departments} busy={busy}
                  onSave={async (change) => {
                    setBusy(true)
                    const ok = await onUpdate(task, change)
                    setBusy(false)
                    if (ok) setEditing(false)
                  }}
                  onCancelTask={cancelTask}
                  onClose={() => setEditing(false)} />
      ) : (
        <div className="actions-row">
          {task.status === 'UNASSIGNED' && chair && (
            <select
              aria-label="담당 부서 정하기"
              value=""
              disabled={busy}
              onChange={(e) => e.target.value && act({ departmentId: Number(e.target.value) })}
            >
              <option value="">담당 부서 정하기</option>
              {org.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          )}
          {task.status === 'ASSIGNED' && canWork && (
            <button type="button" className="primary inline" disabled={busy} onClick={() => act({ status: 'IN_PROGRESS' })}>
              시작
            </button>
          )}
          {task.status === 'IN_PROGRESS' && canWork && (
            <button type="button" className="primary inline" disabled={busy} onClick={() => act({ status: 'SUBMITTED' })}>
              결과 제출
            </button>
          )}
          {task.status === 'SUBMITTED' && chair && (
            <>
              <button type="button" className="primary inline" disabled={busy} onClick={() => act({ status: 'DONE' })}>
                완료 확정
              </button>
              <button type="button" className="ghost" disabled={busy} onClick={() => act({ status: 'IN_PROGRESS' })}>
                반려
              </button>
            </>
          )}
          {task.status === 'SUBMITTED' && !chair && <span className="muted small">회의 책임자 확인 대기</span>}
        </div>
      )}
    </article>
  )
}

interface EditTaskProps {
  task: BoardTask
  departments: { id: number; name: string }[]
  busy: boolean
  onSave: (change: TaskPatch) => void
  onCancelTask: () => void
  onClose: () => void
}

/** 회의 책임자용 편집: 내용·담당·기한. 바뀐 칸만 보낸다. */
function EditTask({ task, departments, busy, onSave, onCancelTask, onClose }: EditTaskProps) {
  const [title, setTitle] = useState(task.title)
  const [departmentId, setDepartmentId] = useState<number | null>(task.departmentId)
  const [dueDate, setDueDate] = useState(task.dueDate ?? '')
  const clearable = canClearDepartment(task.status)

  function save() {
    const change: TaskPatch = {}
    if (title.trim() !== task.title) change.title = title.trim()
    if (departmentId !== task.departmentId) change.departmentId = departmentId
    if ((dueDate || null) !== task.dueDate) change.dueDate = dueDate || null
    if (Object.keys(change).length === 0) {
      onClose()
      return
    }
    onSave(change)
  }

  return (
    <div className="edit-task">
      <label className="compact">
        업무 내용
        <textarea value={title} onChange={(e) => setTitle(e.target.value)} rows={2} maxLength={300} />
      </label>
      <label className="compact">
        담당 부서
        <select value={departmentId ?? ''} onChange={(e) => setDepartmentId(e.target.value ? Number(e.target.value) : null)}>
          {clearable && <option value="">미정</option>}
          {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </label>
      <label className="compact">
        기한
        <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
      </label>
      <p className="muted small">상태: {TASK_STATUS_LABEL[task.status]}</p>
      <div className="actions-row">
        <button type="button" className="primary inline" disabled={busy || !title.trim()} onClick={save}>저장</button>
        <button type="button" className="ghost" disabled={busy} onClick={onClose}>닫기</button>
        <button type="button" className="ghost danger" disabled={busy} onClick={onCancelTask}>업무 취소</button>
      </div>
    </div>
  )
}
