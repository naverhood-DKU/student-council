import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { formatDateTime } from '../labels'
import { errorMessage } from '../useOrg'

/** 내 가입 신청 상태와, 가입 코드로 다른 그룹에 신청하는 칸 */
export function JoinPanel() {
  const { state, refresh } = useAuth()
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (state.status !== 'signed-in') return null
  const requests = state.me.joinRequests

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.applyJoin(code)
      setCode('')
      await refresh()
    } catch (e) {
      setError(errorMessage(e, '가입 신청을 하지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <h3>가입 신청</h3>
      {requests.length > 0 && (
        <ul className="row-list small">
          {requests.map((r) => (
            <li key={r.id} className="member-row">
              <span>
                <strong>{r.orgName}</strong>
                <span className="muted block">{formatDateTime(r.requestedAt)} 신청</span>
                {r.rejectReason && <span className="block">거절 사유: {r.rejectReason}</span>}
              </span>
              <span className={`chip ${r.status === 'REJECTED' ? 'warn' : ''}`}>
                {r.status === 'PENDING' ? '회장 승인 대기' : '거절됨'}
              </span>
            </li>
          ))}
        </ul>
      )}
      <form className="actions-row" onSubmit={submit}>
        <input aria-label="가입 코드" value={code} onChange={(e) => setCode(e.target.value)} maxLength={30}
               placeholder="가입 코드 (예: ce)" autoCapitalize="none" spellCheck={false} className="code-input" />
        <button type="submit" className="ghost" disabled={busy || !code.trim()}>
          {requests.some((r) => r.status === 'REJECTED') ? '신청 / 다시 신청' : '신청'}
        </button>
      </form>
      {requests.some((r) => r.status === 'PENDING') && (
        <button type="button" className="link-button small" onClick={() => refresh()}>승인됐는지 다시 확인</button>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  )
}
