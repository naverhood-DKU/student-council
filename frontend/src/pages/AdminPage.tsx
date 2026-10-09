import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router'
import { api, type AdminGroup, type OrgType } from '../api'
import { useAuth } from '../auth'
import { ORG_TYPE_LABEL } from '../labels'
import { errorMessage, useLoad } from '../useOrg'
import { TopBar } from './TopBar'

/**
 * 서비스 관리: 그룹(자치회)을 만들고 각 그룹에 회장(owner) 계정을 준다.
 * 그룹 내용은 "열람"으로 읽기만 할 수 있다.
 */
export function AdminPage() {
  const { state } = useAuth()
  if (state.status !== 'signed-in') return null
  if (!state.me.serviceAdmin) return <Navigate to="/orgs" replace />
  return (
    <>
      <TopBar />
      <main className="page">
        <h2>서비스 관리</h2>
        <p className="muted small">
          그룹을 만들고 회장(owner) 계정을 줍니다. 부장·부원 초대는 각 그룹 회장이 합니다.
          그룹 내용은 열람만 할 수 있고 고칠 수 없습니다.
        </p>
        <Groups />
      </main>
    </>
  )
}

function Groups() {
  const [groups, reload] = useLoad<AdminGroup[]>(() => api.adminGroups(), [], '그룹 목록을 불러오지 못했습니다.')
  const [creating, setCreating] = useState(false)

  if (groups.status === 'loading') return <p className="page-message">불러오는 중…</p>
  if (groups.status === 'error') return <p className="error" role="alert">{groups.message}</p>

  return (
    <>
      {!creating && <button type="button" className="primary inline" onClick={() => setCreating(true)}>그룹 만들기</button>}
      {creating && (
        <CreateGroupForm
          groups={groups.data}
          onDone={() => {
            setCreating(false)
            reload()
          }}
          onCancel={() => setCreating(false)}
        />
      )}
      <div className="stack">
        {groups.data.map((g) => <GroupCard key={g.id} group={g} onChanged={reload} />)}
      </div>
    </>
  )
}

function GroupCard({ group: g, onChanged }: { group: AdminGroup; onChanged: () => void }) {
  const [granting, setGranting] = useState(false)
  const [loginId, setLoginId] = useState('')
  const [name, setName] = useState('')
  const [initialPassword, setInitialPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      setGranting(false)
      setLoginId('')
      setName('')
      setInitialPassword('')
      onChanged()
    } catch (e) {
      setError(errorMessage(e, fallback))
    } finally {
      setBusy(false)
    }
  }

  function grant(event: FormEvent) {
    event.preventDefault()
    run(() => api.adminGrantOwner(g.id, { loginId, name, initialPassword }), '회장 계정을 주지 못했습니다.')
  }

  function revoke(userId: number, ownerName: string) {
    if (window.confirm(`${g.name}에서 ${ownerName}님의 회장(owner) 자격을 거둘까요?`)) {
      run(() => api.adminRevokeOwner(g.id, userId), '회장 자격을 거두지 못했습니다.')
    }
  }

  return (
    <article className="card">
      <div className="card-title-row">
        <div>
          <span className="chip">{ORG_TYPE_LABEL[g.type]} · {g.term} 임기</span>
          <h3>{g.name}</h3>
          <p className="muted small">
            {g.parentName ? `상위: ${g.parentName} · ` : ''}구성원 {g.memberCount}명{!g.active && ' · 비활성'}
          </p>
        </div>
        <Link to={`/orgs/${g.id}`} className="ghost">열람</Link>
      </div>
      <JoinCodeRow group={g} onChanged={onChanged} />
      <p className="small"><strong>회장(owner)</strong></p>
      {g.owners.length === 0 && <p className="warn-text small">아직 회장이 없습니다.</p>}
      <ul className="row-list small">
        {g.owners.map((o) => (
          <li key={o.userId} className="member-row">
            <span>{o.name} <span className="muted">{o.loginId}</span></span>
            <button type="button" className="ghost danger" disabled={busy} onClick={() => revoke(o.userId, o.name)}>
              자격 거두기
            </button>
          </li>
        ))}
      </ul>
      {!granting ? (
        <button type="button" className="ghost" onClick={() => setGranting(true)}>회장 계정 주기</button>
      ) : (
        <form className="form-card" onSubmit={grant}>
          <label className="compact">
            아이디
            <input value={loginId} onChange={(e) => setLoginId(e.target.value)} maxLength={255} required autoComplete="off" />
          </label>
          <label className="compact">
            이름 (새 계정일 때)
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} />
          </label>
          <label className="compact">
            처음 비밀번호 (새 계정일 때, 8자 이상)
            <input type="password" value={initialPassword} onChange={(e) => setInitialPassword(e.target.value)}
                   autoComplete="new-password" />
          </label>
          <div className="actions-row">
            <button type="submit" className="primary inline" disabled={busy || !loginId.trim()}>주기</button>
            <button type="button" className="ghost" onClick={() => setGranting(false)}>취소</button>
          </div>
        </form>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </article>
  )
}

/** 가입 코드 보기·바꾸기. 코드가 없으면 그 그룹은 가입 신청을 받지 않는다. */
function JoinCodeRow({ group: g, onChanged }: { group: AdminGroup; onChanged: () => void }) {
  const [editing, setEditing] = useState(false)
  const [code, setCode] = useState(g.joinCode ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.adminChangeJoinCode(g.id, code)
      setEditing(false)
      onChanged()
    } catch (e) {
      setError(errorMessage(e, '가입 코드를 바꾸지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  if (!editing) {
    return (
      <p className="small">
        가입 코드 {g.joinCode ? <strong className="code">{g.joinCode}</strong> : <span className="warn-text">없음(가입 신청을 받지 않음)</span>}{' '}
        <button type="button" className="link-button" onClick={() => setEditing(true)}>{g.joinCode ? '바꾸기' : '정하기'}</button>
      </p>
    )
  }
  return (
    <form className="actions-row" onSubmit={save}>
      <input aria-label="가입 코드" value={code} onChange={(e) => setCode(e.target.value)} maxLength={30} required
             autoCapitalize="none" spellCheck={false} className="code-input" />
      <button type="submit" className="primary inline" disabled={busy || !code.trim()}>저장</button>
      <button type="button" className="ghost" onClick={() => setEditing(false)}>취소</button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}

function CreateGroupForm({ groups, onDone, onCancel }: { groups: AdminGroup[]; onDone: () => void; onCancel: () => void }) {
  const [name, setName] = useState('')
  const [joinCode, setJoinCode] = useState('')
  const [type, setType] = useState<OrgType>('DEPARTMENT')
  const [parentId, setParentId] = useState<number | null>(null)
  const [term, setTerm] = useState(String(new Date().getFullYear()))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.adminCreateGroup({ name, joinCode, type, parentId, term })
      onDone()
    } catch (e) {
      setError(errorMessage(e, '그룹을 만들지 못했습니다.'))
      setBusy(false)
    }
  }

  return (
    <form className="card form-card" onSubmit={submit}>
      <h3>그룹 만들기</h3>
      <label className="compact">
        이름
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} required placeholder="예: 전자전기공학부 학생회" />
      </label>
      <label className="compact">
        가입 코드 (부장·부원이 회원가입할 때 넣음)
        <input value={joinCode} onChange={(e) => setJoinCode(e.target.value)} maxLength={30} required
               autoCapitalize="none" spellCheck={false} placeholder="예: ee" />
      </label>
      <label className="compact">
        종류 (표시용)
        <select value={type} onChange={(e) => setType(e.target.value as OrgType)}>
          {(Object.keys(ORG_TYPE_LABEL) as OrgType[]).map((t) => <option key={t} value={t}>{ORG_TYPE_LABEL[t]}</option>)}
        </select>
      </label>
      <label className="compact">
        상위 그룹 (표시용, 선택)
        <select value={parentId ?? ''} onChange={(e) => setParentId(e.target.value ? Number(e.target.value) : null)}>
          <option value="">없음</option>
          {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
        </select>
      </label>
      <label className="compact">
        임기
        <input value={term} onChange={(e) => setTerm(e.target.value)} maxLength={20} required />
      </label>
      <p className="muted small">기획부·홍보부·재정부가 기본 부서로 만들어집니다.</p>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions-row">
        <button type="submit" className="primary inline" disabled={busy || !name.trim() || !joinCode.trim()}>만들기</button>
        <button type="button" className="ghost" onClick={onCancel}>취소</button>
      </div>
    </form>
  )
}
