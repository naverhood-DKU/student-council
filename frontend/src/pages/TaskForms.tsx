import { useState, type FormEvent } from 'react'
import { api, type DecisionOption, type TaskStatus } from '../api'
import { errorMessage } from '../useOrg'

type Department = { id: number; name: string }

/** 진행 중·제출된 업무는 담당을 비울 수 없다(다른 부서로만 바꾸기). */
export function canClearDepartment(status: TaskStatus): boolean {
  return status === 'PROPOSED' || status === 'UNASSIGNED' || status === 'ASSIGNED'
}

interface AddTaskFormProps {
  departments: Department[]
  /** 결정을 정해 두면 고르는 칸이 없다(검토 화면). 없으면 decisions 에서 고른다(업무보드). */
  decisionId?: number
  decisions?: DecisionOption[]
  onDone: () => void
  onCancel: () => void
}

/** 회의 책임자가 업무를 직접 추가하는 작은 양식 */
export function AddTaskForm({ departments, decisionId, decisions, onDone, onCancel }: AddTaskFormProps) {
  const [chosenDecision, setChosenDecision] = useState<number | null>(decisionId ?? decisions?.[0]?.id ?? null)
  const [title, setTitle] = useState('')
  const [departmentId, setDepartmentId] = useState<number | null>(null)
  const [dueDate, setDueDate] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (chosenDecision == null) return
    setBusy(true)
    setError(null)
    try {
      await api.addTask(chosenDecision, { title: title.trim(), departmentId, dueDate: dueDate || null })
      onDone()
    } catch (e) {
      setError(errorMessage(e, '업무를 추가하지 못했습니다.'))
      setBusy(false)
    }
  }

  if (decisionId == null && decisions && decisions.length === 0) {
    return (
      <div className="card form-card">
        <p className="empty">업무를 붙일 승인된 결정이 없습니다. 회의 결정을 먼저 승인해 주세요.</p>
        <button type="button" className="ghost" onClick={onCancel}>닫기</button>
      </div>
    )
  }

  return (
    <form className="card form-card" onSubmit={submit}>
      <h3>업무 추가</h3>
      {decisionId == null && decisions && (
        <label className="compact">
          어느 결정의 업무인가요?
          <select value={chosenDecision ?? ''} onChange={(e) => setChosenDecision(Number(e.target.value))}>
            {decisions.map((d) => (
              <option key={d.id} value={d.id}>[{d.meetingTitle}] {d.summary}</option>
            ))}
          </select>
        </label>
      )}
      <label className="compact">
        할 일
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={300} required autoFocus />
      </label>
      <div className="task-fields">
        <label className="compact">
          담당 부서
          <select value={departmentId ?? ''} onChange={(e) => setDepartmentId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">미정</option>
            {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <label className="compact">
          기한
          <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        </label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="submit" className="primary inline" disabled={busy || !title.trim() || chosenDecision == null}>
          추가
        </button>
        <button type="button" className="ghost" onClick={onCancel}>취소</button>
      </div>
    </form>
  )
}
