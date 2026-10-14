// 화면에 보이는 한국어 이름표
import type {
  AiDecisionStatus,
  DecisionStatus,
  DeliveryStatus,
  RequestType,
  Tally,
  JobStatus,
  MeetingStatus,
  MembershipRole,
  OrgType,
  TaskStatus,
} from './api'

export const ORG_TYPE_LABEL: Record<OrgType, string> = {
  STUDENT_UNION: '총학생회',
  COLLEGE: '단과대',
  DEPARTMENT: '학과',
}

export const ROLE_LABEL: Record<MembershipRole, string> = {
  OWNER: '회장(owner)',
  EDITOR: '부장(editor)',
  VIEWER: '부원(viewer)',
}

/** 직책의 권한 수준 */
export const LEVEL_LABEL: Record<'EDITOR' | 'VIEWER', string> = {
  EDITOR: '부장급',
  VIEWER: '부원급',
}

/** 화면에 보이는 직책: 직책 이름이 있으면 그것, 없으면(회장) 역할 이름 */
export function positionLabel(m: { role: MembershipRole; positionName: string | null }): string {
  return m.positionName ?? ROLE_LABEL[m.role]
}

/** 업무 완전 삭제 전 확인. 기록을 남기려면 취소를 쓰라고 함께 알린다. */
export function confirmDeleteTask(title: string): boolean {
  return window.confirm(
    `「${title}」 업무를 완전히 지울까요? 체크리스트도 함께 지워지고 되돌릴 수 없습니다.\n`
      + '기록을 남기고 멈추기만 하려면 삭제 대신 업무 취소를 쓰세요.',
  )
}

export const MEETING_STATUS_LABEL: Record<MeetingStatus, string> = {
  READY: '분석 전',
  ANALYZING: '분석 중',
  ANALYZED: '검토 대기',
  ANALYSIS_FAILED: '분석 실패',
}

export const JOB_STATUS_LABEL: Record<JobStatus, string> = {
  QUEUED: '대기 중',
  RUNNING: '분석 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
  FAILED_UNKNOWN: '결과 확인 불가',
}

export const DECISION_STATUS_LABEL: Record<DecisionStatus, string> = {
  PROPOSED: 'AI 후보',
  APPROVED: '승인됨',
  ON_HOLD: '보류',
  CHANGED: '변경됨',
  CANCELLED: '취소됨',
}

export const AI_STATUS_LABEL: Record<AiDecisionStatus, string> = {
  CONFIRMED: 'AI: 확정 발언',
  ON_HOLD: 'AI: 보류 발언',
  DISCUSSING: 'AI: 논의만 함',
}

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PROPOSED: '후보',
  UNASSIGNED: '담당 미정',
  ASSIGNED: '할 일',
  IN_PROGRESS: '진행',
  SUBMITTED: '결과 제출',
  DONE: '완료',
  CANCELLED: '취소됨',
}

export const DELIVERY_STATUS_LABEL: Record<DeliveryStatus, string> = {
  PENDING: '대기',
  CLARIFICATION_REQUESTED: '확인 요청',
  ACCEPTED: '수락',
  IN_PROGRESS: '진행',
  SUBMITTED: '결과 제출',
  DONE: '완료',
  NOT_APPLICABLE: '해당 없음',
  CANCELLED: '취소',
}

export const REQUEST_TYPE_LABEL: Record<RequestType, string> = {
  INFORM: '알림',
  COOPERATE: '협조 요청',
  RESULT_NOTICE: '결과 보고 요청',
}

/** "완료 1 / 확인 요청 1 / 전체 2" — 0인 상태는 뺀다. 전체(분모)는 해당 없음·취소를 뺀 수. */
export function tallyText(tally: Tally): string {
  const order: DeliveryStatus[] = [
    'DONE', 'SUBMITTED', 'IN_PROGRESS', 'ACCEPTED', 'CLARIFICATION_REQUESTED', 'PENDING', 'NOT_APPLICABLE', 'CANCELLED',
  ]
  const parts = order
    .filter((s) => tally.counts[s] > 0)
    .map((s) => `${DELIVERY_STATUS_LABEL[s]} ${tally.counts[s]}`)
  return [...parts, `전체 ${tally.total}`].join(' / ')
}

/** 오늘(내 PC 날짜)보다 기한이 지났고 아직 안 끝난 업무 */
export function isOverdue(dueDate: string | null, status: string): boolean {
  if (!dueDate || status === 'DONE' || status === 'CANCELLED' || status === 'PROPOSED') return false
  const today = new Date()
  const local = new Date(today.getTime() - today.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
  return dueDate < local
}

/** 작업 실패 사유(서버 오류 종류)를 사람이 읽을 문장으로 바꾼다. */
export function jobErrorText(code: string | null): string {
  if (!code) return '알 수 없는 이유로 실패했습니다.'
  if (code === 'AI_KEY_MISSING') return 'CLOVA Studio 키가 설정되지 않았습니다. 관리자에게 알려 주세요.'
  if (code === 'AI_TIMEOUT')
    return 'AI 응답이 제한 시간 안에 오지 않았습니다. AI가 처리했는지 알 수 없어 자동으로 다시 부르지 않았습니다.'
  if (code === 'AI_INVALID_RESPONSE') return 'AI 응답 형식이 맞지 않았습니다(한 번 다시 요청했지만 같았습니다).'
  if (code === 'AI_RESPONSE_TRUNCATED') return 'AI 응답이 너무 길어 중간에 잘렸습니다. 회의록을 나눠 올려 주세요.'
  if (code === 'AI_CONNECT_FAILED') return 'AI 서버에 연결하지 못했습니다.'
  if (code === 'AI_HTTP_429') return 'AI 사용량 한도에 걸렸습니다.'
  if (code.startsWith('AI_HTTP_5')) return 'AI 서버에 일시적인 오류가 났습니다.'
  if (code.startsWith('AI_HTTP_')) return `AI 서버가 요청을 거절했습니다(${code}).`
  if (code === 'SPEECH_KEY_MISSING') return 'CLOVA Speech 키가 설정되지 않았습니다. 관리자에게 알려 주세요.'
  if (code === 'SPEECH_URL_INVALID') return 'CLOVA Speech 주소(Invoke URL) 설정이 잘못됐습니다. 관리자에게 알려 주세요.'
  if (code === 'SPEECH_AUDIO_MISSING') return '올린 녹음 파일을 찾지 못했습니다. 다시 올려 주세요.'
  if (code === 'STORAGE_NOT_CONFIGURED') return '녹음 저장소 설정이 비어 있습니다. 관리자에게 알려 주세요.'
  if (code === 'SPEECH_EMPTY_RESULT') return '녹음에서 말소리를 찾지 못했습니다.'
  if (code === 'SPEECH_TIMEOUT')
    return '녹음 변환이 제한 시간 안에 끝나지 않았습니다. 처리됐는지 알 수 없어 자동으로 다시 부르지 않았습니다.'
  if (code === 'SPEECH_CONNECT_FAILED') return 'CLOVA Speech 서버에 연결하지 못했습니다.'
  if (code === 'SPEECH_HTTP_401' || code === 'SPEECH_HTTP_403') return 'CLOVA Speech 키가 맞지 않습니다. 관리자에게 알려 주세요.'
  if (code.startsWith('SPEECH_HTTP_')) return `CLOVA Speech 서버가 요청을 처리하지 못했습니다(${code}).`
  if (code === 'MAX_ATTEMPTS_EXCEEDED') return '여러 번 시도했지만 끝내지 못했습니다.'
  if (code === 'ALREADY_REVIEWED') return '이미 검토를 마친 결정이 있어 결과를 덮어쓰지 않았습니다.'
  if (code === 'TRANSCRIPT_CHANGED') return '분석하는 동안 회의록이 바뀌었습니다. 다시 분석해 주세요.'
  if (code === 'EMPTY_TRANSCRIPT') return '회의록에 발언이 없습니다.'
  return `처리 중 오류가 났습니다(${code}).`
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
}
