import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { useAuth } from '../auth'
import { positionLabel } from '../labels'
import { errorMessage } from '../useOrg'
import { JoinPanel } from './JoinPanel'
import { TopBar } from './TopBar'

/** 내 계정: 소속 확인과 비밀번호 변경. 초대받은 처음 비밀번호는 여기서 바꾼다. */
export function AccountPage() {
  const { state } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  if (state.status !== 'signed-in') return null
  const { me } = state

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (next !== confirm) {
      setError('새 비밀번호 두 칸이 다릅니다.')
      return
    }
    setBusy(true)
    setError(null)
    setDone(false)
    try {
      await api.changePassword(current, next)
      setCurrent('')
      setNext('')
      setConfirm('')
      setDone(true)
    } catch (e) {
      setError(errorMessage(e, '비밀번호를 바꾸지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <TopBar />
      <main className="page narrow">
        <p><Link to="/orgs" className="muted small">← 그룹 선택</Link></p>
        <h2>내 계정</h2>
        <section className="card">
          <p><strong>{me.name}</strong> <span className="muted small">{me.email}</span></p>
          {me.serviceAdmin && <p className="small">서비스 관리자</p>}
          <ul className="row-list small">
            {me.memberships.map((m) => (
              <li key={`${m.orgId}-${m.role}`}>
                {m.orgName} · {positionLabel(m)}{m.departmentName && ` · ${m.departmentName}`}
              </li>
            ))}
          </ul>
        </section>
        <JoinPanel />
        <form className="card form-card" onSubmit={submit}>
          <h3>비밀번호 바꾸기</h3>
          <label className="compact">
            지금 비밀번호
            <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)}
                   autoComplete="current-password" required />
          </label>
          <label className="compact">
            새 비밀번호 (8자 이상)
            <input type="password" value={next} onChange={(e) => setNext(e.target.value)}
                   autoComplete="new-password" minLength={8} required />
          </label>
          <label className="compact">
            새 비밀번호 한 번 더
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)}
                   autoComplete="new-password" minLength={8} required />
          </label>
          {error && <p className="error" role="alert">{error}</p>}
          {done && <p className="notice small" role="status">비밀번호를 바꿨습니다.</p>}
          <button type="submit" className="primary inline" disabled={busy}>바꾸기</button>
        </form>
      </main>
    </>
  )
}
