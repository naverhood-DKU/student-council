import { useState, type FormEvent } from 'react'
import { api, ApiError, type DepartmentSetting, type MembershipRole, type OrgDetail, type PositionSetting } from '../api'
import { LEVEL_LABEL } from '../labels'
import { errorMessage, isOwner, useLoad } from '../useOrg'

type Level = 'EDITOR' | 'VIEWER'

const LEVEL_HELP: Record<Level, string> = {
  EDITOR: '자기 부서 업무 시작·결과 제출, 체크리스트로 부서 사람에게 일 맡기기',
  VIEWER: '보기, 자기에게 맡겨진 체크리스트 체크',
}

/** 실패하면 오류를 보여 주고, 버전 충돌이면 최신 목록을 다시 불러온다. */
function useAction(onChanged: () => void) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<unknown>, fallback: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
      return true
    } catch (e) {
      const conflict = e instanceof ApiError && e.code === 'VERSION_CONFLICT'
      setError(conflict ? '다른 사람이 먼저 바꿨습니다. 최신 내용을 다시 불러왔습니다.' : errorMessage(e, fallback))
      if (conflict) onChanged()
      return false
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, run }
}

// ---- 부서 ----

/**
 * 부서 설정: 이름과 역할 키워드. 역할 키워드는 AI가 회의록에서 담당 부서를 추천할 때 쓴다.
 * 없앤 부서는 목록·선택에서 빠지고 지난 기록에만 남는다.
 */
export function DepartmentsTab({ org, onChanged }: { org: OrgDetail; onChanged: () => void }) {
  const [list, reload] = useLoad<DepartmentSetting[]>(
    () => api.departmentSettings(org.id),
    [org.id],
    '부서를 불러오지 못했습니다.',
  )
  const owner = isOwner(org)
  const changed = () => {
    reload()
    onChanged()
  }

  return (
    <>
      <p className="muted small">
        역할 키워드는 AI가 회의록에서 담당 부서를 추천할 때 씁니다(쉼표로 나눔). 없앤 부서는 지난 업무 기록에만 남고,
        구성원이나 끝나지 않은 업무가 있으면 없앨 수 없습니다.
      </p>
      {owner && <AddDepartment orgId={org.id} onDone={changed} />}
      {list.status === 'loading' && <p className="page-message">불러오는 중…</p>}
      {list.status === 'error' && <p className="error" role="alert">{list.message}</p>}
      {list.status === 'ready' && (
        <ul className="card row-list">
          {list.data.map((d) => (
            <DepartmentRow key={`${d.id}-${d.version}`} department={d} editable={owner} onChanged={changed} />
          ))}
        </ul>
      )}
    </>
  )
}

function AddDepartment({ orgId, onDone }: { orgId: number; onDone: () => void }) {
  const [name, setName] = useState('')
  const [keywords, setKeywords] = useState('')
  const { busy, error, run } = useAction(onDone)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const ok = await run(() => api.addDepartment(orgId, { name, roleKeywords: keywords }), '부서를 만들지 못했습니다.')
    if (ok) {
      setName('')
      setKeywords('')
    }
  }

  return (
    <form className="card setup-add" onSubmit={submit}>
      <input aria-label="새 부서 이름" value={name} onChange={(e) => setName(e.target.value)} maxLength={30}
             placeholder="새 부서 이름 (예: 디자인부)" required />
      <input aria-label="역할 키워드" value={keywords} onChange={(e) => setKeywords(e.target.value)} maxLength={500}
             placeholder="역할 키워드 (예: 포스터,디자인,굿즈)" />
      <button type="submit" className="primary inline" disabled={busy || !name.trim()}>부서 추가</button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}

function DepartmentRow({ department: d, editable, onChanged }: {
  department: DepartmentSetting
  editable: boolean
  onChanged: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(d.name)
  const [keywords, setKeywords] = useState(d.roleKeywords ?? '')
  const { busy, error, run } = useAction(onChanged)

  function save(event: FormEvent) {
    event.preventDefault()
    const change: { name?: string; roleKeywords?: string } = {}
    if (name.trim() !== d.name) change.name = name.trim()
    if (keywords !== (d.roleKeywords ?? '')) change.roleKeywords = keywords
    if (Object.keys(change).length === 0) {
      setEditing(false)
      return
    }
    run(() => api.updateDepartment(d, change), '부서를 바꾸지 못했습니다.')
  }

  function remove() {
    if (window.confirm(`${d.name}을(를) 없앨까요? 지난 업무 기록에는 이름이 남습니다.`)) {
      run(() => api.removeDepartment(d.id), '부서를 없애지 못했습니다.')
    }
  }

  if (editing) {
    return (
      <li>
        <form className="setup-edit" onSubmit={save}>
          <input aria-label="부서 이름" value={name} onChange={(e) => setName(e.target.value)} maxLength={30} required />
          <input aria-label="역할 키워드" value={keywords} onChange={(e) => setKeywords(e.target.value)} maxLength={500}
                 placeholder="역할 키워드(쉼표로 나눔)" />
          <div className="actions-row">
            <button type="submit" className="primary inline" disabled={busy || !name.trim()}>저장</button>
            <button type="button" className="ghost" disabled={busy} onClick={() => setEditing(false)}>닫기</button>
          </div>
          {error && <p className="error" role="alert">{error}</p>}
        </form>
      </li>
    )
  }

  return (
    <li className="member-row">
      <div>
        <strong>{d.name}</strong>
        <span className="muted small block">
          구성원 {d.memberCount}명 · 끝나지 않은 업무 {d.openTaskCount}건(승인 전 후보 포함)
        </span>
        <span className="muted small block">키워드: {d.roleKeywords ? d.roleKeywords.replaceAll(',', ', ') : '없음'}</span>
      </div>
      {editable && (
        <div className="actions-row">
          <button type="button" className="ghost" disabled={busy} onClick={() => setEditing(true)}>편집</button>
          <button type="button" className="ghost danger" disabled={busy} onClick={remove}>없애기</button>
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </li>
  )
}

// ---- 직책 ----

/**
 * 직책 설정: 이름과 권한 수준(부장급·부원급). 구성원의 권한은 직책을 따른다.
 * 권한 수준을 바꾸면 그 직책을 가진 사람의 권한도 바로 바뀐다.
 */
export function PositionsTab({ org, positions, onChanged }: {
  org: OrgDetail
  positions: PositionSetting[]
  onChanged: () => void
}) {
  const owner = isOwner(org)
  return (
    <>
      <ul className="level-help small">
        <li><strong>{LEVEL_LABEL.EDITOR}</strong>: {LEVEL_HELP.EDITOR}. 부서가 있어야 합니다.</li>
        <li><strong>{LEVEL_LABEL.VIEWER}</strong>: {LEVEL_HELP.VIEWER}.</li>
        <li className="muted">회장(owner)은 직책이 아니라 서비스 관리자가 정하는 자리입니다.</li>
      </ul>
      {owner && <AddPosition orgId={org.id} onDone={onChanged} />}
      <ul className="card row-list">
        {positions.map((p) => (
          <PositionRow key={`${p.id}-${p.version}`} position={p} editable={owner} onChanged={onChanged} />
        ))}
      </ul>
    </>
  )
}

function LevelSelect({ value, onChange, disabled }: { value: Level; onChange: (level: Level) => void; disabled?: boolean }) {
  return (
    <select aria-label="권한 수준" value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as Level)}>
      <option value="EDITOR">{LEVEL_LABEL.EDITOR}</option>
      <option value="VIEWER">{LEVEL_LABEL.VIEWER}</option>
    </select>
  )
}

function AddPosition({ orgId, onDone }: { orgId: number; onDone: () => void }) {
  const [name, setName] = useState('')
  const [level, setLevel] = useState<Level>('VIEWER')
  const { busy, error, run } = useAction(onDone)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const ok = await run(() => api.addPosition(orgId, { name, level }), '직책을 만들지 못했습니다.')
    if (ok) setName('')
  }

  return (
    <form className="card setup-add" onSubmit={submit}>
      <input aria-label="새 직책 이름" value={name} onChange={(e) => setName(e.target.value)} maxLength={30}
             placeholder="새 직책 이름 (예: 부회장, 총무)" required />
      <LevelSelect value={level} onChange={setLevel} />
      <button type="submit" className="primary inline" disabled={busy || !name.trim()}>직책 추가</button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}

function PositionRow({ position: p, editable, onChanged }: {
  position: PositionSetting
  editable: boolean
  onChanged: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(p.name)
  const [level, setLevel] = useState<Level>(p.level === 'EDITOR' ? 'EDITOR' : 'VIEWER')
  const { busy, error, run } = useAction(onChanged)

  function save(event: FormEvent) {
    event.preventDefault()
    const change: { name?: string; level?: MembershipRole } = {}
    if (name.trim() !== p.name) change.name = name.trim()
    if (level !== p.level) {
      if (p.memberCount > 0 && !window.confirm(
        `이 직책을 가진 ${p.memberCount}명의 권한도 ${LEVEL_LABEL[level]}으로 바뀝니다. 계속할까요?`)) return
      change.level = level
    }
    if (Object.keys(change).length === 0) {
      setEditing(false)
      return
    }
    run(() => api.updatePosition(p, change), '직책을 바꾸지 못했습니다.')
  }

  function remove() {
    if (window.confirm(`${p.name} 직책을 지울까요?`)) {
      run(() => api.removePosition(p.id), '직책을 지우지 못했습니다.')
    }
  }

  if (editing) {
    return (
      <li>
        <form className="setup-edit" onSubmit={save}>
          <input aria-label="직책 이름" value={name} onChange={(e) => setName(e.target.value)} maxLength={30} required />
          <LevelSelect value={level} onChange={setLevel} disabled={busy} />
          <div className="actions-row">
            <button type="submit" className="primary inline" disabled={busy || !name.trim()}>저장</button>
            <button type="button" className="ghost" disabled={busy} onClick={() => setEditing(false)}>닫기</button>
          </div>
          {error && <p className="error" role="alert">{error}</p>}
        </form>
      </li>
    )
  }

  const pLevel: Level = p.level === 'EDITOR' ? 'EDITOR' : 'VIEWER'
  return (
    <li className="member-row">
      <div>
        <strong>{p.name}</strong> <span className={`chip inline-chip${pLevel === 'EDITOR' ? ' mine-chip' : ''}`}>{LEVEL_LABEL[pLevel]}</span>
        <span className="muted small block">{p.memberCount}명 · {LEVEL_HELP[pLevel]}</span>
      </div>
      {editable && (
        <div className="actions-row">
          <button type="button" className="ghost" disabled={busy} onClick={() => setEditing(true)}>편집</button>
          <button type="button" className="ghost danger" disabled={busy} onClick={remove}>지우기</button>
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </li>
  )
}
