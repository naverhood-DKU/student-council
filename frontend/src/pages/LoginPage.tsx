import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router'
import { ApiError } from '../api'
import { useAuth } from '../auth'

export function LoginPage() {
  const { state, login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  if (state.status === 'signed-in') return <Navigate to="/orgs" replace />

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login(email, password)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '로그인하지 못했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  const notice = state.status === 'signed-out' ? state.reason : undefined

  return (
    <main className="login">
      <div className="login-card">
        <h1>학생자치 결정 추적</h1>
        <p className="muted">회의에서 정한 일을 끝까지 챙깁니다.</p>
        {notice && <p className="notice">{notice}</p>}
        <form onSubmit={onSubmit}>
          <label>
            아이디(이메일)
            <input
              type="text"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </label>
          <label>
            비밀번호
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>
          {error && <p className="error" role="alert">{error}</p>}
          <button type="submit" className="primary" disabled={submitting}>
            {submitting ? '로그인 중…' : '로그인'}
          </button>
        </form>
        <p className="muted small">계정은 학생회 관리자의 초대로만 만들 수 있습니다.</p>
      </div>
    </main>
  )
}
