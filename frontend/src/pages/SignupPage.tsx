import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import { useAuth } from '../auth'
import { errorMessage } from '../useOrg'

/**
 * 회원가입: 그룹 회장에게 받은 가입 코드를 넣고 계정을 만든다.
 * 계정은 바로 생기지만, 회장이 역할(부장·부원)과 부서를 정해 승인해야 그룹 화면을 쓸 수 있다.
 * 회장이 알려 준 링크(/signup?code=ce)로 들어오면 코드가 미리 채워진다.
 */
export function SignupPage() {
  const { state, login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [groupCode, setGroupCode] = useState(params.get('code') ?? '')
  const [loginId, setLoginId] = useState('')
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  if (state.status === 'signed-in') return <Navigate to="/orgs" replace />

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (password !== confirm) {
      setError('비밀번호 두 칸이 다릅니다.')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await api.signUp({ loginId, name, password, groupCode })
      await login(loginId, password)
      navigate('/orgs', { replace: true })
    } catch (e) {
      setError(errorMessage(e, '가입하지 못했습니다.'))
      setSubmitting(false)
    }
  }

  return (
    <main className="login">
      <div className="login-card">
        <h1>회원가입</h1>
        <p className="muted small">
          학생회 회장에게 받은 <strong>가입 코드</strong>를 넣어 주세요. 회장이 승인하면서 직책(부장·부원)과 부서를 정해 줍니다.
        </p>
        <form onSubmit={onSubmit}>
          <label>
            가입 코드
            <input value={groupCode} onChange={(e) => setGroupCode(e.target.value)} maxLength={30} required
                   autoCapitalize="none" spellCheck={false} placeholder="예: ce" />
          </label>
          <label>
            아이디
            <input value={loginId} onChange={(e) => setLoginId(e.target.value)} maxLength={255} required
                   autoComplete="username" autoCapitalize="none" spellCheck={false} />
          </label>
          <label>
            이름
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} required autoComplete="name" />
          </label>
          <label>
            비밀번호 (8자 이상)
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                   autoComplete="new-password" minLength={8} required />
          </label>
          <label>
            비밀번호 한 번 더
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)}
                   autoComplete="new-password" minLength={8} required />
          </label>
          {error && <p className="error" role="alert">{error}</p>}
          <button type="submit" className="primary" disabled={submitting}>
            {submitting ? '가입하는 중…' : '가입 신청'}
          </button>
        </form>
        <p className="muted small">이미 계정이 있나요? <Link to="/login">로그인</Link>한 뒤 내 계정에서 다른 그룹에 신청할 수 있습니다.</p>
      </div>
    </main>
  )
}
