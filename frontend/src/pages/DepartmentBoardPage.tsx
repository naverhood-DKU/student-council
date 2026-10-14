import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  api,
  ApiError,
  type BoardTask,
  type Member,
  type OrgDetail,
  type TaskItem,
  type TaskPatch,
  type TaskStatus,
} from '../api'
import { useAuth } from '../auth'
import { RuleCheckBadge } from './RuleCheckBadge'
import { isOverdue, positionLabel, TASK_STATUS_LABEL } from '../labels'
import { canWorkFor, errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { canClearDepartment } from './TaskForms'
import { TopBar } from './TopBar'

const COLUMNS: { key: string; label: string; statuses: TaskStatus[] }[] = [
  { key: 'todo', label: '할 일', statuses: ['UNASSIGNED', 'ASSIGNED'] },
  { key: 'doing', label: '진행', statuses: ['IN_PROGRESS'] },
  { key: 'submitted', label: '결과 제출', statuses: ['SUBMITTED'] },
  { key: 'done', label: '완료', statuses: ['DONE'] },
]

/** 업무보드 트리에서 고른 부서 하나의 업무. 주소의 departmentKey 는 부서 id 또는 담당 미정(none). */
export function DepartmentBoardPage() {
  const params = useParams()
  const orgId = Number(params.orgId)
  const departmentId = params.departmentKey === 'none' ? null : Number(params.departmentKey)
  const [org] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page wide">
        <p><Link to={`/orgs/${orgId}/board`} className="muted small">← 업무보드</Link></p>
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <DepartmentBoard org={org.data} departmentId={departmentId} />}
      </main>
    </>
  )
}

interface BoardData {
  tasks: BoardTask[]
  members: Member[]
}

function DepartmentBoard({ org, departmentId }: { org: OrgDetail; departmentId: number | null }) {
  const [data, reload] = useLoad<BoardData>(
    async () => ({ tasks: await api.board(org.id), members: await api.members(org.id) }),
    [org.id],
    '업무를 불러오지 못했습니다.',
  )
  const [overdueOnly, setOverdueOnly] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const chair = isOwner(org)
  const department = org.departments.find((d) => d.id === departmentId)

  if (data.status === 'loading') return <p className="page-message">불러오는 중…</p>
  if (data.status === 'error') return <p className="error">{data.message}</p>
  // 없앤 부서면 업무에 남은 부서 이름으로 보여 준다(끝난 업무 기록).
  const retiredName = department ? null : data.data.tasks.find((t) => t.departmentId === departmentId)?.departmentName
  if (departmentId != null && !department && !retiredName) return <p className="error">이 그룹의 부서가 아닙니다.</p>
  const title = department ? department.name : retiredName ? `${retiredName} (없앤 부서)` : '담당 미정'

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

  const { tasks, members } = data.data
  const mine = tasks.filter((t) => t.departmentId === departmentId)
  const visible = mine.filter((t) => !overdueOnly || isOverdue(t.dueDate, t.status))
  const cancelled = visible.filter((t) => t.status === 'CANCELLED')
  const overdueCount = mine.filter((t) => isOverdue(t.dueDate, t.status)).length
  const people = members.filter((m) => departmentId != null && m.departmentId === departmentId)
  const leads = people.filter((m) => m.role === 'EDITOR')
  const crew = people.filter((m) => m.role === 'VIEWER')

  return (
    <>
      <section className="org-header">
        <h2>{title} 업무</h2>
        {department ? (
          <p className="muted small">
            부장 {leads.length > 0 ? leads.map((m) => m.name).join(', ') : '없음'}
            {' · '}부원 {crew.length > 0 ? crew.map((m) => m.name).join(', ') : '없음'}
          </p>
        ) : retiredName ? (
          <p className="muted small">없앤 부서가 맡았던 업무 기록입니다.</p>
        ) : (
          <p className="muted small">담당 부서가 정해지지 않은 업무입니다. 회장이 부서를 정하면 그 부서로 옮겨 갑니다.</p>
        )}
      </section>
      <div className="actions-row">
        <label className="compact inline-label check">
          <input type="checkbox" checked={overdueOnly} onChange={(e) => setOverdueOnly(e.target.checked)} />
          기한 지남만{overdueCount > 0 && ` (${overdueCount})`}
        </label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="board">
        {COLUMNS.map((column) => {
          const items = visible.filter((t) => column.statuses.includes(t.status))
          return (
            <section key={column.key} className="board-column">
              <h3>{column.label} <span className="muted small">{items.length}</span></h3>
              {items.length === 0 && <p className="empty small">없음</p>}
              {items.map((task) => (
                <TaskCard key={`${task.id}-${task.version}`} task={task} org={org} chair={chair}
                          people={people} onUpdate={update} onChanged={reload} />
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
              <li key={t.id} className="muted small">{t.title} · {t.meetingTitle}</li>
            ))}
          </ul>
        </details>
      )}
    </>
  )
}

interface TaskCardProps {
  task: BoardTask
  org: OrgDetail
  chair: boolean
  /** 이 부서 사람(체크리스트를 맡길 수 있는 사람) */
  people: Member[]
  onUpdate: (task: BoardTask, change: TaskPatch) => Promise<boolean>
  onChanged: () => void
}

function TaskCard({ task, org, chair, people, onUpdate, onChanged }: TaskCardProps) {
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(false)
  const canWork = canWorkFor(org, task.departmentId)
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
        기한 {task.dueDate ?? '미정'}
        {overdue && <span className="chip warn inline-chip">기한 지남</span>}
        {task.origin === 'MANUAL' && <span className="chip subtle inline-chip">직접 추가</span>}
      </p>
      <p className="small clamp">
        <Link to={`/orgs/${org.id}/meetings/${task.meetingId}`}>{task.meetingTitle}</Link>: {task.decisionSummary}
      </p>

      {!finished && <RuleCheckBadge taskId={task.id} initial={task.ruleCheck} />}

      {task.departmentId != null && (
        <Checklist task={task} manager={canWork} people={people} onChanged={onChanged} />
      )}

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
          {task.status === 'SUBMITTED' && !chair && <span className="muted small">회장 확인 대기</span>}
        </div>
      )}
    </article>
  )
}

/**
 * 체크리스트: 회장·담당 부서 부장(manager)이 할 일을 잘게 나눠 부서 사람에게 맡긴다.
 * 맡은 사람은 자기 줄의 완료만 체크한다. 완료·취소된 업무는 고칠 수 없다.
 */
function Checklist({ task, manager, people, onChanged }: {
  task: BoardTask
  manager: boolean
  people: Member[]
  onChanged: () => void
}) {
  const { state } = useAuth()
  const myId = state.status === 'signed-in' ? state.me.id : null
  const open = task.status !== 'DONE' && task.status !== 'CANCELLED'
  const [title, setTitle] = useState('')
  const [assignee, setAssignee] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const items = task.items
  const doneCount = items.filter((i) => i.done).length

  if (items.length === 0 && !(manager && open)) return null

  async function run(action: () => Promise<unknown>, fallback: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
      return true
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'VERSION_CONFLICT'
        ? '다른 사람이 먼저 바꿨습니다. 최신 상태를 다시 불러왔습니다.'
        : errorMessage(e, fallback))
      if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') onChanged()
      return false
    } finally {
      setBusy(false)
    }
  }

  async function add(event: FormEvent) {
    event.preventDefault()
    const ok = await run(() => api.addTaskItem(task.id, { title, assigneeUserId: assignee }), '할 일을 추가하지 못했습니다.')
    if (ok) setTitle('')
  }

  function remove(item: TaskItem) {
    if (window.confirm(`"${item.title}"을(를) 체크리스트에서 지울까요?`)) {
      run(() => api.removeTaskItem(item.id), '지우지 못했습니다.')
    }
  }

  return (
    <div className="checklist">
      <p className="small checklist-head">
        체크리스트 <strong>{doneCount}/{items.length}</strong>
      </p>
      {items.length > 0 && (
        <ul className="checklist-items">
          {items.map((item) => {
            const mine = item.assigneeUserId != null && item.assigneeUserId === myId
            const canCheck = open && (manager || mine)
            return (
              <li key={`${item.id}-${item.version}`} className={mine ? 'mine' : undefined}>
                <label className="check-row">
                  <input type="checkbox" checked={item.done} disabled={!canCheck || busy}
                         onChange={(e) => run(() => api.updateTaskItem(item, { done: e.target.checked }), '체크하지 못했습니다.')} />
                  <span className={item.done ? 'done-text' : undefined}>{item.title}</span>
                </label>
                {manager && open ? (
                  <span className="checklist-tools">
                    <select aria-label="맡길 사람" value={item.assigneeUserId ?? ''} disabled={busy}
                            onChange={(e) => run(
                              () => api.updateTaskItem(item, { assigneeUserId: e.target.value ? Number(e.target.value) : null }),
                              '맡길 사람을 바꾸지 못했습니다.',
                            )}>
                      <option value="">안 맡김</option>
                      {people.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
                    </select>
                    <button type="button" className="link-button danger" aria-label="지우기" disabled={busy}
                            onClick={() => remove(item)}>×</button>
                  </span>
                ) : (
                  <span className={`chip inline-chip${mine ? ' mine-chip' : ''}`}>
                    {mine ? '나' : item.assigneeName ?? '안 맡김'}
                  </span>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {manager && open && (
        <form className="checklist-add" onSubmit={add}>
          <input aria-label="할 일" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={300}
                 placeholder="할 일 추가" />
          <select aria-label="맡길 사람" value={assignee ?? ''}
                  onChange={(e) => setAssignee(e.target.value ? Number(e.target.value) : null)}>
            <option value="">안 맡김</option>
            {people.map((m) => <option key={m.userId} value={m.userId}>{m.name} · {positionLabel(m)}</option>)}
          </select>
          <button type="submit" className="ghost" disabled={busy || !title.trim()}>추가</button>
        </form>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
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

/** 회장(owner)용 편집: 내용·담당·기한. 바뀐 칸만 보낸다. */
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
      <p className="muted small">상태: {TASK_STATUS_LABEL[task.status]}. 담당 부서를 바꾸면 이 업무는 그 부서 화면으로 옮겨 갑니다.</p>
      <div className="actions-row">
        <button type="button" className="primary inline" disabled={busy || !title.trim()} onClick={save}>저장</button>
        <button type="button" className="ghost" disabled={busy} onClick={onClose}>닫기</button>
        <button type="button" className="ghost danger" disabled={busy} onClick={onCancelTask}>업무 취소</button>
      </div>
    </div>
  )
}
