import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, type RuleCheck } from '../api'
import './rulecheck.css'

/** 규정 확인 중(PENDING)일 때 결과를 다시 확인하는 간격과 최대 횟수 */
const POLL_MS = 3000
const POLL_MAX = 100

const KIND_LABEL: Record<string, string> = {
  PROHIBITED: '금지',
  LIMITED: '제한',
  CONFLICT: '충돌',
}

/**
 * 돈과 관련된 업무 카드의 규정 확인 표시.
 * - "금지 가능성"·"위험"은 확정이 아니라 가능성이다. 근거 조항과 원문에서 확인된 인용 구절을 항상 같이 보여 준다.
 * - 표시가 없다고 안전한 것이 아니다. 관련 조항을 찾지 못하면 "규정 확인 안 됨"으로 알린다.
 * - 확인 대상이 아닌 카드(check 가 null)는 아무것도 그리지 않는다.
 * - "관련 조항 보기"는 지출 절차 확인 화면으로 이어져 조항 원문(DB 값 그대로)을 보여 준다.
 */
export function RuleCheckBadge({ taskId, initial }: { taskId: number; initial: RuleCheck | null }) {
  const orgId = Number(useParams().orgId)
  const [check, setCheck] = useState<RuleCheck | null>(initial)

  // 부모가 새 값을 주면(목록을 다시 불러온 경우) 그 값을 따른다.
  useEffect(() => setCheck(initial), [initial])

  // AI 안내가 끝나기 전에는 결과가 나올 때까지 이 카드의 표시만 다시 불러온다.
  const pending = check?.badge === 'PENDING'
  useEffect(() => {
    if (!pending) return
    let tries = 0
    let stopped = false
    const timer = window.setInterval(async () => {
      tries += 1
      try {
        const next = await api.taskRuleCheck(taskId)
        if (!stopped && next && next.badge !== 'PENDING') {
          setCheck(next)
        }
      } catch {
        // 다음 주기에 다시 시도한다. 계속 실패해도 카드의 다른 기능에는 영향이 없다.
      }
      if (tries >= POLL_MAX) window.clearInterval(timer)
    }, POLL_MS)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [pending, taskId])

  if (!check) return null
  const level = check.badge.toLowerCase()

  return (
    <div className={`rulecheck rulecheck-${level}`} data-badge={check.badge}>
      <div className="rulecheck-head">
        <span className={`rulecheck-badge rulecheck-badge-${level}`}>{check.label}</span>
        {check.reviewId > 0 && (
          <Link to={`/orgs/${orgId}/expense/${check.reviewId}`} className="rulecheck-link">
            관련 조항 보기{check.candidateCount > 0 ? ` (${check.candidateCount})` : ''}
          </Link>
        )}
      </div>
      <p className="muted small rulecheck-summary">{check.summary}</p>
      {check.evidence.length > 0 && (
        <ul className="rulecheck-evidence">
          {check.evidence.map((e, i) => (
            <li key={`${e.kind}-${i}`}>
              <strong>{KIND_LABEL[e.kind] ?? e.kind}</strong> {e.text}
              {e.clauses.length > 0 && (
                <span className="muted small">
                  {' '}— {e.clauses.map((c) => `${c.docTitle} ${c.label}`).join(', ')}
                </span>
              )}
              {e.quote && <blockquote className="rulecheck-quote">{e.quote}</blockquote>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
