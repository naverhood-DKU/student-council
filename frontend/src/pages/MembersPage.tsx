import { useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, ApiError, type Member, type OrgDetail, type PendingJoinRequest, type PositionSetting } from '../api'
import { formatDateTime, LEVEL_LABEL, positionLabel } from '../labels'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { DepartmentsTab, PositionsTab } from './GroupSetupTabs'
import { TopBar } from './TopBar'

type Tab = 'members' | 'departments' | 'positions'

const TABS: { key: Tab; label: string }[] = [
  { key: 'members', label: '구성원' },
  { key: 'departments', label: '부서' },
  { key: 'positions', label: '직책' },
]

/**
 * 그룹 구성: 구성원 · 부서 · 직책.
 * 부장·부원은 가입 코드로 회원가입해 신청하고, 회장이 직책과 부서를 정해 승인한다.
 * 권한은 직책의 권한 수준(부장급·부원급)을 따른다. 회장(owner) 자리는 서비스 관리자만 준다.
 * 보기는 구성원 누구나, 바꾸기는 회장만.
 */
export function MembersPage() {
  const orgId = Number(useParams().orgId)
  const [org, reloadOrg] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <GroupSettings org={org.data} reloadOrg={reloadOrg} />}
      </main>
    </>
  )
}

function GroupSettings({ org, reloadOrg }: { org: OrgDetail; reloadOrg: () => void }) {
  const [params, setParams] = useSearchParams()
  const tab = TABS.find((t) => t.key === params.get('tab'))?.key ?? 'members'
  const [positions, reloadPositions] = useLoad<PositionSetting[]>(
    () => api.positions(org.id),
    [org.id],
    '직책을 불러오지 못했습니다.',
  )

  return (
    <>
      <h2>{org.name} 구성</h2>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.key} type="button" role="tab" aria-selected={tab === t.key}
                  className={tab === t.key ? 'tab active' : 'tab'}
                  onClick={() => setParams(t.key === 'members' ? {} : { tab: t.key }, { replace: true })}>
            {t.label}
            {t.key === 'departments' && <span className="tab-count">{org.departments.length}</span>}
            {t.key === 'positions' && positions.status === 'ready' && (
              <span className="tab-count">{positions.data.length}</span>
            )}
          </button>
        ))}
      </div>
      {positions.status === 'error' && <p className="error" role="alert">{positions.message}</p>}
      {tab === 'members' && positions.status === 'ready' && (
        <MembersTab org={org} positions={positions.data} onPositionsChanged={reloadPositions} />
      )}
      {tab === 'departments' && <DepartmentsTab org={org} onChanged={reloadOrg} />}
      {tab === 'positions' && positions.status === 'ready' && (
        <PositionsTab org={org} positions={positions.data} onChanged={reloadPositions} />
      )}
    </>
  )
}

function MembersTab({ org, positions, onPositionsChanged }: {
  org: OrgDetail
  positions: PositionSetting[]
  onPositionsChanged: () => void
}) {
  const [members, reload] = useLoad<Member[]>(() => api.members(org.id), [org.id], '구성원을 불러오지 못했습니다.')
  const [adding, setAdding] = useState(false)
  const owner = isOwner(org)
  const changed = () => {
    reload()
    onPositionsChanged()
  }

  return (
    <>
      <p className="muted small">
        회장은 회의·업무·요청을 모두 다룹니다. 부장급 직책은 자기 부서 업무를 시작·제출하고 체크리스트로 부서 사람에게 일을 나눠 주며,
        부원급 직책은 보면서 자기에게 맡겨진 체크리스트를 체크합니다.
      </p>
      {owner && <JoinCode org={org} />}
      {owner && <JoinRequests org={org} positions={positions} onApproved={changed} />}
      {owner && !adding && (
        <button type="button" className="ghost" onClick={() => setAdding(true)}>아이디를 직접 만들어 초대</button>
      )}
      {adding && (
        <AddForm
          org={org}
          positions={positions}
          onDone={() => {
            setAdding(false)
            changed()
          }}
          onCancel={() => setAdding(false)}
        />
      )}
      {members.status === 'loading' && <p className="page-message">불러오는 중…</p>}
      {members.status === 'error' && <p className="error" role="alert">{members.message}</p>}
      {members.status === 'ready' && (
        <ul className="card row-list">
          {members.data.map((m) => (
            <MemberRow key={`${m.membershipId}-${m.version}`} member={m} org={org} positions={positions}
                       editable={owner} onChanged={changed} />
          ))}
        </ul>
      )}
    </>
  )
}

/** 가입 코드와 가입 링크. 부장·부원에게 알려 주면 그 사람이 직접 가입 신청한다. */
function JoinCode({ org }: { org: OrgDetail }) {
  const [copied, setCopied] = useState(false)
  if (!org.joinCode) {
    return <p className="notice small">아직 가입 코드가 없습니다. 서비스 관리자에게 가입 코드를 정해 달라고 요청하세요.</p>
  }
  const link = `${window.location.origin}/signup?code=${encodeURIComponent(org.joinCode)}`

  async function copy() {
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <section className="card join-code">
      <p className="small">
        가입 코드 <strong className="code">{org.joinCode}</strong> — 부장·부원에게 알려 주면 회원가입할 때 넣습니다.
      </p>
      <div className="actions-row">
        <input aria-label="가입 링크" value={link} readOnly onFocus={(e) => e.target.select()} />
        <button type="button" className="ghost" onClick={copy}>{copied ? '복사됨' : '링크 복사'}</button>
      </div>
    </section>
  )
}

/** 직책 고르기. 직책 이름 옆에 권한 수준을 같이 보여 준다. */
function PositionSelect({ positions, value, onChange, disabled }: {
  positions: PositionSetting[]
  value: number | null
  onChange: (position: PositionSetting) => void
  disabled?: boolean
}) {
  return (
    <select aria-label="직책" value={value ?? ''} disabled={disabled}
            onChange={(e) => {
              const picked = positions.find((p) => p.id === Number(e.target.value))
              if (picked) onChange(picked)
            }}>
      {value == null && <option value="">직책 고르기</option>}
      {positions.map((p) => (
        <option key={p.id} value={p.id}>{p.name} · {p.level === 'EDITOR' ? LEVEL_LABEL.EDITOR : LEVEL_LABEL.VIEWER}</option>
      ))}
    </select>
  )
}

/** 부서 고르기. 부장급 직책이면 '부서 없음'을 고를 수 없다. */
function DepartmentSelect({ org, value, needed, onChange, disabled }: {
  org: OrgDetail
  value: number | null
  needed: boolean
  onChange: (departmentId: number | null) => void
  disabled?: boolean
}) {
  return (
    <select aria-label="부서" value={value ?? ''} disabled={disabled}
            onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
      {(!needed || value == null) && <option value="">{needed ? '부서 고르기' : '부서 없음'}</option>}
      {org.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
    </select>
  )
}

/** 새로 들어오는 사람의 기본 직책: 부원급 첫 직책, 없으면 첫 직책 */
function defaultPosition(positions: PositionSetting[]): PositionSetting | undefined {
  return positions.find((p) => p.level === 'VIEWER') ?? positions[0]
}

/** 처리 전 가입 신청: 직책과 부서를 정해 승인하거나 거절한다. */
function JoinRequests({ org, positions, onApproved }: {
  org: OrgDetail
  positions: PositionSetting[]
  onApproved: () => void
}) {
  const [requests, reload] = useLoad<PendingJoinRequest[]>(
    () => api.joinRequests(org.id),
    [org.id],
    '가입 신청을 불러오지 못했습니다.',
  )
  if (requests.status === 'loading') return null
  if (requests.status === 'error') return <p className="error" role="alert">{requests.message}</p>
  if (requests.data.length === 0) return null

  return (
    <section className="card">
      <h3>가입 신청 <span className="count-badge">{requests.data.length}</span></h3>
      <ul className="row-list">
        {requests.data.map((r) => (
          <JoinRequestRow key={`${r.id}-${r.version}`} request={r} org={org} positions={positions}
                          onDone={(approved) => {
                            reload()
                            if (approved) onApproved()
                          }} />
        ))}
      </ul>
    </section>
  )
}

function JoinRequestRow({ request: r, org, positions, onDone }: {
  request: PendingJoinRequest
  org: OrgDetail
  positions: PositionSetting[]
  onDone: (approved: boolean) => void
}) {
  const [position, setPosition] = useState<PositionSetting | undefined>(() => defaultPosition(positions))
  const [departmentId, setDepartmentId] = useState<number | null>(org.departments[0]?.id ?? null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const needsDepartment = position?.level === 'EDITOR'

  async function run(action: () => Promise<unknown>, approved: boolean, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onDone(approved)
    } catch (e) {
      setError(errorMessage(e, fallback))
      setBusy(false)
      if (e instanceof ApiError && (e.code === 'VERSION_CONFLICT' || e.code === 'INVALID_TRANSITION')) onDone(false)
    }
  }

  function approve() {
    if (!position) return
    run(() => api.approveJoin(r, { positionId: position.id, departmentId }), true, '승인하지 못했습니다.')
  }

  function reject() {
    const reason = window.prompt(`${r.name}(${r.loginId})님의 가입 신청을 거절할까요? 사유를 적으면 신청한 사람에게 보입니다.`)
    if (reason !== null) run(() => api.rejectJoin(r, reason), false, '거절하지 못했습니다.')
  }

  return (
    <li className="member-row">
      <div>
        <strong>{r.name}</strong> <span className="muted small">{r.loginId}</span>
        <span className="muted small block">{formatDateTime(r.requestedAt)} 신청</span>
      </div>
      <div className="actions-row">
        <PositionSelect positions={positions} value={position?.id ?? null} disabled={busy} onChange={setPosition} />
        <DepartmentSelect org={org} value={departmentId} needed={needsDepartment} disabled={busy}
                          onChange={setDepartmentId} />
        <button type="button" className="primary inline"
                disabled={busy || !position || (needsDepartment && departmentId == null)} onClick={approve}>
          승인
        </button>
        <button type="button" className="ghost danger" disabled={busy} onClick={reject}>거절</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
    </li>
  )
}

function MemberRow({ member: m, org, positions, editable, onChanged }: {
  member: Member
  org: OrgDetail
  positions: PositionSetting[]
  editable: boolean
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 회장 자리는 여기서 바꾸지 않는다(서비스 관리자 몫).
  const changeable = editable && m.role !== 'OWNER'

  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'VERSION_CONFLICT'
        ? '다른 사람이 먼저 바꿨습니다. 최신 목록을 다시 불러왔습니다.'
        : errorMessage(e, fallback))
      if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') onChanged()
      setBusy(false)
    }
  }

  function changePosition(position: PositionSetting) {
    // 부장급은 부서가 있어야 한다. 부서가 없으면 첫 부서를 붙인다.
    const departmentId = position.level === 'EDITOR' ? (m.departmentId ?? org.departments[0]?.id ?? null) : m.departmentId
    run(() => api.updateMember(m, { positionId: position.id, departmentId }), '직책을 바꾸지 못했습니다.')
  }

  function remove() {
    if (window.confirm(`${m.name}(${m.loginId})님을 이 그룹에서 내보낼까요?`)) {
      run(() => api.removeMember(m.membershipId), '내보내지 못했습니다.')
    }
  }

  return (
    <li className="member-row">
      <div>
        <strong>{m.name}</strong> <span className="muted small">{m.loginId}</span>
        {!changeable && (
          <span className="muted small block">
            {positionLabel(m)}{m.departmentName && ` · ${m.departmentName}`}
          </span>
        )}
      </div>
      {changeable && (
        <div className="actions-row">
          <PositionSelect positions={positions} value={m.positionId} disabled={busy} onChange={changePosition} />
          <DepartmentSelect org={org} value={m.departmentId} needed={m.role === 'EDITOR'} disabled={busy}
                            onChange={(departmentId) => run(() => api.updateMember(m, { departmentId }),
                              '부서를 바꾸지 못했습니다.')} />
          <button type="button" className="ghost danger" disabled={busy} onClick={remove}>내보내기</button>
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </li>
  )
}

function AddForm({ org, positions, onDone, onCancel }: {
  org: OrgDetail
  positions: PositionSetting[]
  onDone: () => void
  onCancel: () => void
}) {
  const [loginId, setLoginId] = useState('')
  const [name, setName] = useState('')
  const [initialPassword, setInitialPassword] = useState('')
  const [position, setPosition] = useState<PositionSetting | undefined>(() => defaultPosition(positions))
  const [departmentId, setDepartmentId] = useState<number | null>(org.departments[0]?.id ?? null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const needsDepartment = position?.level === 'EDITOR'

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!position) return
    setBusy(true)
    setError(null)
    try {
      await api.addMember(org.id, { loginId, name, initialPassword, positionId: position.id, departmentId })
      onDone()
    } catch (e) {
      setError(errorMessage(e, '초대하지 못했습니다.'))
      setBusy(false)
    }
  }

  return (
    <form className="card form-card" onSubmit={submit}>
      <h3>직접 초대</h3>
      <label className="compact">
        아이디
        <input value={loginId} onChange={(e) => setLoginId(e.target.value)} maxLength={255} required
               autoComplete="off" placeholder="예: ce.plan2" />
      </label>
      <label className="compact">
        이름
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} />
      </label>
      <label className="compact">
        처음 비밀번호 (8자 이상)
        <input type="password" value={initialPassword} onChange={(e) => setInitialPassword(e.target.value)}
               autoComplete="new-password" />
      </label>
      <p className="muted small">이미 있는 아이디면 이름·비밀번호는 쓰지 않고 이 그룹 소속만 붙입니다.</p>
      <label className="compact">
        직책
        <PositionSelect positions={positions} value={position?.id ?? null} onChange={setPosition} />
      </label>
      <label className="compact">
        부서{needsDepartment && ' (부장급은 필수)'}
        <DepartmentSelect org={org} value={departmentId} needed={needsDepartment} onChange={setDepartmentId} />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="submit" className="primary inline"
                disabled={busy || !loginId.trim() || !position || (needsDepartment && departmentId == null)}>
          초대
        </button>
        <button type="button" className="ghost" onClick={onCancel}>취소</button>
      </div>
    </form>
  )
}
