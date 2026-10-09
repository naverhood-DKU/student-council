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

/** 그룹 회장(owner): 회의·승인·업무 편집·그룹 간 요청·구성원 관리 */
export function isOwner(org: OrgDetail): boolean {
  return org.myRole === 'OWNER'
}

/** 이 부서의 일을 시작·제출할 수 있나: owner, 또는 그 부서의 부장(editor). 부원(viewer)은 조회만 */
export function canWorkFor(org: OrgDetail, departmentId: number | null): boolean {
  if (isOwner(org)) return true
  return org.myRole === 'EDITOR' && departmentId != null && org.myDepartmentIds.includes(departmentId)
}
