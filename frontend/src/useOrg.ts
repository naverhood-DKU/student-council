import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type OrgDetail } from './api'

export type Loadable<T> =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: T }

export function errorMessage(e: unknown, fallback: string): string {
  if (e instanceof ApiError && e.status === 403) return '이 조직에 접근할 권한이 없습니다.'
  if (e instanceof ApiError) return e.message
  return fallback
}

/** 화면에 필요한 값을 불러오고, reload 로 다시 불러온다. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[], fallback: string) {
  const [state, setState] = useState<Loadable<T>>({ status: 'loading' })
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    loader()
      .then((data) => !cancelled && setState({ status: 'ready', data }))
      .catch((e) => !cancelled && setState({ status: 'error', message: errorMessage(e, fallback) }))
    return () => {
      cancelled = true
    }
  }, [...deps, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return [state, reload] as const
}

/** 현재 조직 정보와 내 역할 */
export function useOrg(orgId: number) {
  return useLoad<OrgDetail>(() => api.org(orgId), [orgId], '조직 정보를 불러오지 못했습니다.')
}

export function isChair(org: OrgDetail): boolean {
  return org.myRoles.includes('MEETING_CHAIR')
}

/** 받은 전달을 수락·확인 요청·담당 지정할 수 있는 역할 */
export function canManageReceived(org: OrgDetail): boolean {
  return org.myRoles.includes('MEETING_CHAIR') || org.myRoles.includes('ORG_ADMIN')
}

export function canUpload(org: OrgDetail): boolean {
  return org.myRoles.includes('MEETING_CHAIR') || org.myRoles.includes('ORG_ADMIN')
}
