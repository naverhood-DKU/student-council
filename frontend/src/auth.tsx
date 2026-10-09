import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, tokenStore, SESSION_EXPIRED_EVENT, type Me } from './api'

type AuthState =
  | { status: 'loading' }
  | { status: 'signed-out'; reason?: string }
  | { status: 'signed-in'; me: Me }

interface AuthContextValue {
  state: AuthState
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(() =>
    tokenStore.get() ? { status: 'loading' } : { status: 'signed-out' },
  )

  // 새로 고침했을 때 남아 있는 토큰으로 내 정보를 다시 불러온다.
  useEffect(() => {
    if (state.status !== 'loading') return
    api
      .me()
      .then((me) => setState({ status: 'signed-in', me }))
      .catch(() => {
        tokenStore.clear()
        setState({ status: 'signed-out' })
      })
  }, [state.status])

  useEffect(() => {
    const onExpired = () => setState({ status: 'signed-out', reason: '로그인 시간이 끝났습니다. 다시 로그인해 주세요.' })
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const token = await api.login(email, password)
    tokenStore.set(token.accessToken)
    const me = await api.me()
    setState({ status: 'signed-in', me })
  }, [])

  const logout = useCallback(() => {
    tokenStore.clear()
    setState({ status: 'signed-out' })
  }, [])

  const value = useMemo(() => ({ state, login, logout }), [state, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('AuthProvider 안에서만 쓸 수 있습니다.')
  return value
}
