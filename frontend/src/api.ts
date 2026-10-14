// 서버 API 호출 모음. 토큰은 탭을 닫으면 사라지는 sessionStorage 에만 둔다.

export type OrgType = 'STUDENT_UNION' | 'COLLEGE' | 'DEPARTMENT'
/** 그룹 안 역할: 회장 / 부장 / 부원 */
export type MembershipRole = 'OWNER' | 'EDITOR' | 'VIEWER'
export type MeetingStatus = 'READY' | 'ANALYZING' | 'ANALYZED' | 'ANALYSIS_FAILED'
export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'FAILED_UNKNOWN'
export type DecisionStatus = 'PROPOSED' | 'APPROVED' | 'ON_HOLD' | 'CHANGED' | 'CANCELLED'
export type AiDecisionStatus = 'CONFIRMED' | 'ON_HOLD' | 'DISCUSSING'
export type EvidenceStatus = 'OK' | 'NEEDS_CHECK'
export type TaskStatus = 'PROPOSED' | 'UNASSIGNED' | 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED' | 'DONE' | 'CANCELLED'
export type TaskOrigin = 'AI' | 'MANUAL'

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export interface MembershipView {
  orgId: number
  orgName: string
  orgType: OrgType
  role: MembershipRole
  /** 직책 이름(회장은 null) */
  positionName: string | null
  departmentId: number | null
  departmentName: string | null
}

export type JoinRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 내 가입 신청(처리 전·거절). 승인된 것은 소속(memberships)으로 보인다. */
export interface MyJoinRequest {
  id: number
  orgId: number
  orgName: string
  status: JoinRequestStatus
  rejectReason: string | null
  requestedAt: string
}

/** 회장이 보는 처리 전 가입 신청 */
export interface PendingJoinRequest {
  id: number
  userId: number
  loginId: string
  name: string
  requestedAt: string
  version: number
}

export interface Me {
  id: number
  email: string
  name: string
  /** 서비스 관리자: 그룹 만들기·owner 부여, 모든 그룹 읽기 전용 열람 */
  serviceAdmin: boolean
  memberships: MembershipView[]
  joinRequests: MyJoinRequest[]
}

export interface Member {
  membershipId: number
  userId: number
  loginId: string
  name: string
  /** 권한(직책의 권한 수준). 회장은 OWNER */
  role: MembershipRole
  positionId: number | null
  positionName: string | null
  departmentId: number | null
  departmentName: string | null
  version: number
}

export interface NewMember {
  loginId: string
  name: string
  initialPassword: string
  positionId: number
  departmentId: number | null
}

/** 직책: 회장이 정한 이름과 권한 수준(EDITOR 부장급 / VIEWER 부원급) */
export interface PositionSetting {
  id: number
  name: string
  level: MembershipRole
  sortOrder: number
  memberCount: number
  version: number
}

/** 부서 설정: 이름과 AI가 담당 부서를 추천할 때 쓰는 역할 키워드 */
export interface DepartmentSetting {
  id: number
  name: string
  roleKeywords: string | null
  memberCount: number
  openTaskCount: number
  version: number
}

export interface AdminGroup {
  id: number
  name: string
  joinCode: string | null
  type: OrgType
  parentId: number | null
  parentName: string | null
  term: string
  active: boolean
  memberCount: number
  owners: { userId: number; loginId: string; name: string }[]
}

export interface OrgDetail {
  id: number
  name: string
  type: OrgType
  parentId: number | null
  term: string
  /** 가입 코드. 부장·부원이 회원가입할 때 넣는다 */
  joinCode: string | null
  /** 내 역할. 서비스 관리자 열람이면 null */
  myRole: MembershipRole | null
  /** 내 직책 이름. 회장·서비스 관리자 열람이면 null */
  myPositionName: string | null
  myDepartmentIds: number[]
  /** 서비스 관리자의 읽기 전용 열람 */
  adminView: boolean
  departments: { id: number; name: string }[]
}

export interface Meeting {
  id: number
  orgId: number
  title: string
  heldAt: string
  sourceType: 'TEXT' | 'AUDIO'
  status: MeetingStatus
  transcriptVersion: number
  version: number
}

export interface Job {
  id: number
  type: string
  targetId: number
  status: JobStatus
  attempts: number
  lastError: string | null
  createdAt: string
  updatedAt: string
  quotaWaitUntil: string | null
}

export interface MeetingDetail {
  meeting: Meeting
  segmentCount: number
  latestJob: Job | null
}

export interface Segment {
  id: number
  label: string
  speaker: string | null
  text: string
}

export interface TaskView {
  id: number
  decisionId: number
  title: string
  status: TaskStatus
  departmentId: number | null
  departmentName: string | null
  ownerDepartmentText: string | null
  suggestedDepartmentId: number | null
  suggestedDepartmentName: string | null
  dueDate: string | null
  evidenceStatus: EvidenceStatus
  validationNotes: string[]
  sourceSegmentIds: number[]
  evidence: Segment[]
  origin: TaskOrigin
  version: number
  /** 돈과 관련된 카드의 규정 확인 표시. 확인 대상이 아니면 null */
  ruleCheck: RuleCheck | null
}

export interface DecisionView {
  id: number
  meetingId: number
  agenda: string
  summary: string
  status: DecisionStatus
  aiStatus: AiDecisionStatus | null
  evidenceStatus: EvidenceStatus
  validationNotes: string[]
  sourceSegmentIds: number[]
  evidence: Segment[]
  expenseProposal: { item: string; amount: number | null; fundSource: string | null } | null
  approvedByName: string | null
  version: number
  tasks: TaskView[]
}

export interface Proposals {
  meeting: Meeting
  departments: { id: number; name: string }[]
  segments: Segment[]
  decisions: DecisionView[]
}

export interface DecisionOption {
  id: number
  agenda: string
  summary: string
  meetingId: number
  meetingTitle: string
  /** 회의에서 돈을 쓰기로 한 언급이 있을 때만. 지출 절차 확인 값을 미리 채우는 데 쓴다. */
  expenseProposal: { item: string; amount: number | null; fundSource: string | null } | null
}

export interface NewTask {
  title: string
  departmentId: number | null
  dueDate: string | null
}

export interface BoardTask {
  id: number
  title: string
  status: TaskStatus
  origin: TaskOrigin
  departmentId: number | null
  departmentName: string | null
  dueDate: string | null
  decisionId: number
  decisionSummary: string
  meetingId: number
  meetingTitle: string
  version: number
  /** 체크리스트: 회장·담당 부서 부장이 부서 사람에게 맡긴 작은 할 일 */
  items: TaskItem[]
  /** 돈과 관련된 카드의 규정 확인 표시. 확인 대상이 아니면 null */
  ruleCheck: RuleCheck | null
}

/**
 * 업무 카드의 규정 확인 표시. 확정이 아니라 가능성이며 안전하다는 뜻이 아니다.
 * PROHIBITED 금지 가능성 / RISK 위험 / NONE 관련 조항 보기 / UNCHECKED 규정 확인 안 됨 / PENDING 확인 중 / FAILED 확인 못 함
 */
export type RuleCheckBadge = 'PROHIBITED' | 'RISK' | 'NONE' | 'UNCHECKED' | 'PENDING' | 'FAILED'

export interface RuleCheckEvidence {
  /** PROHIBITED 금지 / LIMITED 제한 / CONFLICT 충돌 */
  kind: string
  text: string
  clauses: { clauseId: number; label: string; docTitle: string; mentionsRestriction: boolean }[]
  /** 근거 조항 원문에서 서버가 확인한 구절 */
  quote: string | null
}

export interface RuleCheck {
  /** 지출 절차 확인 화면(/orgs/:orgId/expense/:reviewId)으로 이어지는 지출 검토 id */
  reviewId: number
  badge: RuleCheckBadge
  label: string
  summary: string
  candidateCount: number
  evidence: RuleCheckEvidence[]
}

export interface TaskItem {
  id: number
  taskId: number
  title: string
  assigneeUserId: number | null
  assigneeName: string | null
  done: boolean
  doneAt: string | null
  version: number
}

export type DeliveryStatus =
  | 'PENDING'
  | 'CLARIFICATION_REQUESTED'
  | 'ACCEPTED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'DONE'
  | 'NOT_APPLICABLE'
  | 'CANCELLED'
export type RequestType = 'INFORM' | 'COOPERATE' | 'RESULT_NOTICE'
export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface GroupOption {
  id: number
  name: string
  type: OrgType
}

export interface SubmissionView {
  id: number
  resultLink: string | null
  description: string | null
  submittedByName: string
  reviewStatus: ReviewStatus
  reviewComment: string | null
  reviewedByName: string | null
  createdAt: string
  reviewedAt: string | null
}

export interface DeliveryView {
  id: number
  decisionId: number
  decisionAgenda: string
  decisionSummary: string
  fromOrgId: number
  fromOrgName: string
  toOrgId: number
  toOrgName: string
  requestType: RequestType
  status: DeliveryStatus
  dueDate: string | null
  previousDueDate: string | null
  dueChangedAt: string | null
  message: string | null
  internalDepartmentId: number | null
  internalDepartmentName: string | null
  clarificationReason: string | null
  clarificationReply: string | null
  closingReason: string | null
  createdAt: string
  version: number
  /** 새 것부터 */
  submissions: SubmissionView[]
}

export interface Tally {
  /** 해당 없음·취소를 뺀 유효 대상 수 */
  total: number
  counts: Record<DeliveryStatus, number>
}

export interface DecisionDeliveries {
  decisionId: number
  agenda: string
  summary: string
  fromOrgId: number
  tally: Tally
  targets: DeliveryView[]
}

export interface SentDecision {
  decisionId: number
  agenda: string
  summary: string
  meetingId: number
  meetingTitle: string
  tally: Tally
}

export interface DeliveryPatch {
  status?: DeliveryStatus
  reason?: string
  clarificationReply?: string
  internalDepartmentId?: number | null
  dueDate?: string | null
}

/** 서버 공통 오류 본문 { code, message, requestId } */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string

  constructor(status: number, code: string, message: string, requestId?: string) {
    super(message)
    this.status = status
    this.code = code
    this.requestId = requestId
  }
}

const TOKEN_KEY = 'councilflow.accessToken'

export const tokenStore = {
  get(): string | null {
    try {
      return sessionStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string) {
    try {
      sessionStorage.setItem(TOKEN_KEY, token)
    } catch {
      // 저장소를 못 쓰는 환경이면 새로 고칠 때 다시 로그인하면 된다.
    }
  },
  clear() {
    try {
      sessionStorage.removeItem(TOKEN_KEY)
    } catch {
      // 무시
    }
  },
}

/** 토큰이 만료되면(401) 이 이벤트를 보내 화면을 로그인으로 돌린다. */
export const SESSION_EXPIRED_EVENT = 'councilflow:session-expired'

async function request<T>(method: string, path: string, body?: unknown, ifMatch?: number): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const isForm = body instanceof FormData
  // FormData(파일 업로드)는 브라우저가 Content-Type(경계값 포함)을 직접 붙인다.
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`
  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.')
  }

  if (!response.ok) {
    const error = await response.json().catch(() => null)
    if (response.status === 401 && token) {
      tokenStore.clear()
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
    }
    throw new ApiError(
      response.status,
      error?.code ?? 'UNKNOWN',
      error?.message ?? '요청을 처리하지 못했습니다.',
      error?.requestId ?? response.headers.get('X-Request-Id') ?? undefined,
    )
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export interface TaskPatch {
  title?: string
  departmentId?: number | null
  dueDate?: string | null
  status?: TaskStatus
}

// ---- 규정 등록·검수·확정 ----

export type RuleDocStatus = 'DRAFT' | 'CONFIRMED' | 'RETIRED'
export type ClauseKind = 'PREAMBLE' | 'CHAPTER' | 'SECTION' | 'ARTICLE' | 'EXTRA' | 'SUPPLEMENTARY'
export type ClauseReviewStatus = 'UNREVIEWED' | 'REVIEWED'

export interface RuleDocSummary {
  id: number
  title: string
  sourceType: 'REGULATION'
  issuer: string
  effectiveDate: string | null
  docVersion: string | null
  status: RuleDocStatus
  articleCount: number
  /** 아직 검수하지 않은 조·보칙·부칙 수 */
  unreviewedCount: number
  roundtripOk: boolean
  version: number
}

export interface ClauseView {
  id: number
  seq: number
  kind: ClauseKind
  label: string
  number: number | null
  branchNumber: number | null
  title: string | null
  chapter: string | null
  section: string | null
  text: string
  reviewStatus: ClauseReviewStatus
  edited: boolean
  flags: string[]
  version: number
}

export interface RuleDocDetail {
  summary: RuleDocSummary
  appliesTo: string | null
  notes: string | null
  fileName: string | null
  sourceSha256: string | null
  reviewedBy: number | null
  reviewedAt: string | null
  registrationWarnings: string[]
  clauses: ClauseView[]
}

export interface RuleDocInput {
  title: string
  issuer: string
  appliesTo: string
  effectiveDate: string
  docVersion: string
  notes: string
  bodyStartLine: string
  file: File | null
  text: string
}


// ---- 지출 절차 검토 ----

export type ExpenseReviewStatus =
  | 'CANDIDATES_FOUND' | 'NEEDS_CHECK' | 'PROCEDURE_FOUND' | 'POSSIBLE_CONFLICT' | 'POSSIBLY_RESTRICTED' | 'REVIEWED'

export interface ExpenseCandidate {
  clauseId: number
  rank: number
  label: string
  title: string | null
  /** 검수된 조항 원문(서버가 DB 의 값을 그대로 보냄) */
  text: string
  docId: number
  docTitle: string
  docVersion: string | null
  docEffectiveDate: string | null
  docStatus: RuleDocStatus
  /** 이 조항 글에 금지·제한 표현이 있다(서버의 낱말 검사) */
  mentionsRestriction: boolean
  /** 조항 원문에서 지출 항목과 글자가 겹치는 문단(원문을 자른 것). 말이 달라 겹치지 않으면 비어 있다 */
  matchedParagraphs: string[]
}

/** 근거 조항으로 연결하기 위한 짧은 참조(조항 원문은 candidates 에서 읽는다) */
export interface ExpenseClauseRef {
  clauseId: number
  label: string
  docTitle: string
  /** 근거 조항 글에 금지·제한 표현이 있다 */
  mentionsRestriction: boolean
}

/** 사용을 금지(PROHIBITED)하거나 한도·조건을 두는(LIMITED) 조항 내용. quote 는 원문에서 확인된 구절 */
export interface ExpenseRestriction {
  kind: 'PROHIBITED' | 'LIMITED'
  text: string
  clauses: ExpenseClauseRef[]
  quote: string
  /** 지출 항목과 직접 관련 있어 보이는가(서버의 글자 조각 비교). 관련 없어 보여도 지우지 않는다. */
  related: boolean
}

/** 필요한 승인(APPROVAL) 또는 필요한 증빙(EVIDENCE). 서버가 인용 구절을 조항 원문에서 확인한 것만 온다. */
export interface ExpenseChecklistItem {
  id: number
  kind: 'APPROVAL' | 'EVIDENCE'
  text: string
  clauses: ExpenseClauseRef[]
  /** 근거 조항 원문에서 확인된 인용 구절 */
  quote: string
  checked: boolean
  checkedAt: string | null
  version: number
}

/** AI 재확인에서 이 지출에 해당하지 않거나 인용과 맞지 않는다고 본 승인·증빙. 체크리스트 밖에 따로 보인다. */
export interface ExpenseSetAside {
  kind: 'APPROVAL' | 'EVIDENCE'
  text: string
  clauses: ExpenseClauseRef[]
  quote: string
  reason: 'NOT_APPLICABLE' | 'QUOTE_MISMATCH'
}

export interface ExpenseConflict {
  description: string
  clauses: ExpenseClauseRef[]
}

/** 서버 검증을 통과한 AI 안내. note 는 조항으로 확인된 내용이 아니라 참고용이다. */
export interface ExpenseGuidance {
  outcome: 'PROCEDURE_FOUND' | 'POSSIBLE_CONFLICT' | 'POSSIBLY_RESTRICTED' | 'NEEDS_CHECK'
  restrictions: ExpenseRestriction[]
  conflicts: ExpenseConflict[]
  setAside: ExpenseSetAside[]
  missingFacts: string[]
  note: string | null
  /** 서버 검증에서 걸러낸 항목 수 */
  droppedCount: number
  droppedNotes: string[]
  generatedAt: string
}

export interface ExpenseReviewView {
  id: number
  orgId: number
  decisionId: number | null
  item: string
  amount: number | null
  fundSource: string | null
  plannedDate: string | null
  note: string | null
  searchText: string | null
  status: ExpenseReviewStatus
  candidates: ExpenseCandidate[]
  /** 결과를 읽을 때 놓치면 안 되는 점 */
  notices: string[]
  /** AI 안내를 아직 못 받았거나 실패했으면 null */
  guidance: ExpenseGuidance | null
  checklist: ExpenseChecklistItem[]
  /** 가장 최근 AI 안내 작업(없으면 null). status 와 lastError 로 진행·실패를 보여 준다. */
  job: Job | null
  /** 서버가 항상 붙이는 고정 안내문 */
  disclaimer: string
  reviewedAt: string | null
  createdAt: string
  version: number
}

export interface ExpenseReviewSummary {
  id: number
  item: string
  amount: number | null
  status: ExpenseReviewStatus
  candidateCount: number
  createdAt: string
}

export interface ExpenseReviewInput {
  orgId: number
  decisionId?: number
  item: string
  amount?: number
  fundSource?: string
  plannedDate?: string
  note?: string
  keywords?: string
}

export const api = {
  login: (email: string, password: string) =>
    request<TokenResponse>('POST', '/api/auth/login', { email, password }),
  signUp: (input: { loginId: string; name: string; password: string; groupCode: string }) =>
    request<{ loginId: string; groupName: string; status: JoinRequestStatus }>('POST', '/api/auth/signup', input),
  applyJoin: (groupCode: string) => request<MyJoinRequest>('POST', '/api/me/join-requests', { groupCode }),
  joinRequests: (orgId: number) => request<PendingJoinRequest[]>('GET', `/api/orgs/${orgId}/join-requests`),
  approveJoin: (req: { id: number; version: number }, input: { positionId: number; departmentId: number | null }) =>
    request<MyJoinRequest>('POST', `/api/join-requests/${req.id}/approve`, input, req.version),
  rejectJoin: (req: { id: number; version: number }, reason: string) =>
    request<MyJoinRequest>('POST', `/api/join-requests/${req.id}/reject`, { reason }, req.version),
  me: () => request<Me>('GET', '/api/me'),
  org: (orgId: number) => request<OrgDetail>('GET', `/api/orgs/${orgId}`),

  meetings: (orgId: number) => request<Meeting[]>('GET', `/api/orgs/${orgId}/meetings`),
  meeting: (meetingId: number) => request<MeetingDetail>('GET', `/api/meetings/${meetingId}`),
  createMeeting: (input: { orgId: number; title: string; heldAt: string; transcript: string }) =>
    request<MeetingDetail>('POST', '/api/meetings', input),
  extractPdf: (orgId: number, file: File) => {
    const form = new FormData()
    form.append('orgId', String(orgId))
    form.append('file', file)
    return request<{ text: string; pageCount: number }>('POST', '/api/meetings/extract-text', form)
  },
  analyze: (meetingId: number) =>
    request<{ jobId: number; job: Job }>('POST', `/api/meetings/${meetingId}/analysis`),
  job: (jobId: number) => request<Job>('GET', `/api/jobs/${jobId}`),
  proposals: (meetingId: number) => request<Proposals>('GET', `/api/meetings/${meetingId}/proposals`),

  editDecision: (decision: DecisionView, change: { agenda?: string; summary?: string }) =>
    request<DecisionView>('PATCH', `/api/decisions/${decision.id}`, change, decision.version),
  approve: (decision: DecisionView) =>
    request<DecisionView>('POST', `/api/decisions/${decision.id}/approve`, undefined, decision.version),
  updateTask: (task: { id: number; version: number }, change: TaskPatch) =>
    request<TaskView>('PATCH', `/api/tasks/${task.id}`, change, task.version),
  board: (orgId: number) => request<BoardTask[]>('GET', `/api/orgs/${orgId}/tasks`),
  addTaskItem: (taskId: number, input: { title: string; assigneeUserId: number | null }) =>
    request<TaskItem>('POST', `/api/tasks/${taskId}/items`, input),
  updateTaskItem: (item: { id: number; version: number }, change: { title?: string; assigneeUserId?: number | null; done?: boolean }) =>
    request<TaskItem>('PATCH', `/api/task-items/${item.id}`, change, item.version),
  removeTaskItem: (itemId: number) => request<void>('DELETE', `/api/task-items/${itemId}`),
  addTask: (decisionId: number, task: NewTask) =>
    request<TaskView>('POST', `/api/decisions/${decisionId}/tasks`, task),
  approvedDecisions: (orgId: number) => request<DecisionOption[]>('GET', `/api/orgs/${orgId}/decisions`),

  requestTargets: (orgId: number) => request<GroupOption[]>('GET', `/api/orgs/${orgId}/request-targets`),

  members: (orgId: number) => request<Member[]>('GET', `/api/orgs/${orgId}/members`),
  addMember: (orgId: number, input: NewMember) => request<Member>('POST', `/api/orgs/${orgId}/members`, input),
  updateMember: (member: { membershipId: number; version: number }, change: { positionId?: number; departmentId?: number | null }) =>
    request<Member>('PATCH', `/api/memberships/${member.membershipId}`, change, member.version),

  departmentSettings: (orgId: number) => request<DepartmentSetting[]>('GET', `/api/orgs/${orgId}/departments`),
  addDepartment: (orgId: number, input: { name: string; roleKeywords: string }) =>
    request<DepartmentSetting>('POST', `/api/orgs/${orgId}/departments`, input),
  updateDepartment: (department: { id: number; version: number }, change: { name?: string; roleKeywords?: string }) =>
    request<DepartmentSetting>('PATCH', `/api/departments/${department.id}`, change, department.version),
  removeDepartment: (departmentId: number) => request<void>('DELETE', `/api/departments/${departmentId}`),
  positions: (orgId: number) => request<PositionSetting[]>('GET', `/api/orgs/${orgId}/positions`),
  addPosition: (orgId: number, input: { name: string; level: MembershipRole }) =>
    request<PositionSetting>('POST', `/api/orgs/${orgId}/positions`, input),
  updatePosition: (position: { id: number; version: number }, change: { name?: string; level?: MembershipRole }) =>
    request<PositionSetting>('PATCH', `/api/positions/${position.id}`, change, position.version),
  removePosition: (positionId: number) => request<void>('DELETE', `/api/positions/${positionId}`),
  removeMember: (membershipId: number) => request<void>('DELETE', `/api/memberships/${membershipId}`),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('POST', '/api/me/password', { currentPassword, newPassword }),

  adminGroups: () => request<AdminGroup[]>('GET', '/api/admin/groups'),
  adminCreateGroup: (input: { name: string; joinCode: string; type: OrgType; parentId: number | null; term: string }) =>
    request<AdminGroup>('POST', '/api/admin/groups', input),
  adminChangeJoinCode: (orgId: number, joinCode: string) =>
    request<AdminGroup>('PATCH', `/api/admin/groups/${orgId}`, { joinCode }),
  adminGrantOwner: (orgId: number, input: { loginId: string; name: string; initialPassword: string }) =>
    request<AdminGroup>('POST', `/api/admin/groups/${orgId}/owners`, input),
  adminRevokeOwner: (orgId: number, userId: number) =>
    request<AdminGroup>('DELETE', `/api/admin/groups/${orgId}/owners/${userId}`),
  sendDeliveries: (
    decisionId: number,
    input: { targetOrgIds: number[]; requestType: RequestType; dueDate: string | null; message: string },
  ) => request<DecisionDeliveries>('POST', `/api/decisions/${decisionId}/deliveries`, input),
  deliverySummary: (decisionId: number) =>
    request<DecisionDeliveries>('GET', `/api/decisions/${decisionId}/delivery-summary`),
  inbox: (orgId: number) => request<DeliveryView[]>('GET', `/api/orgs/${orgId}/inbox`),
  sentDeliveries: (orgId: number) => request<SentDecision[]>('GET', `/api/orgs/${orgId}/sent-deliveries`),
  updateDelivery: (delivery: { id: number; version: number }, change: DeliveryPatch) =>
    request<DeliveryView>('PATCH', `/api/deliveries/${delivery.id}`, change, delivery.version),
  submitResult: (deliveryId: number, input: { resultLink: string; description: string }) =>
    request<DeliveryView>('POST', `/api/deliveries/${deliveryId}/submissions`, input),
  reviewSubmission: (submissionId: number, approve: boolean, comment: string) =>
    request<DeliveryView>('POST', `/api/submissions/${submissionId}/review`, { approve, comment }),

  ruleDocuments: (orgId: number) => request<RuleDocSummary[]>('GET', `/api/orgs/${orgId}/rule-documents`),
  ruleDocument: (docId: number) => request<RuleDocDetail>('GET', `/api/rule-documents/${docId}`),
  registerRuleDocument: (orgId: number, input: RuleDocInput) => {
    const form = new FormData()
    form.append('title', input.title)
    form.append('issuer', input.issuer)
    if (input.appliesTo) form.append('appliesTo', input.appliesTo)
    if (input.effectiveDate) form.append('effectiveDate', input.effectiveDate)
    if (input.docVersion) form.append('docVersion', input.docVersion)
    if (input.notes) form.append('notes', input.notes)
    if (input.bodyStartLine) form.append('bodyStartLine', input.bodyStartLine)
    if (input.file) form.append('file', input.file)
    else form.append('text', input.text)
    return request<RuleDocDetail>('POST', `/api/orgs/${orgId}/rule-documents`, form)
  },
  updateRuleDocument: (
    doc: { id: number; version: number },
    change: { title?: string; issuer?: string; appliesTo?: string; effectiveDate?: string; docVersion?: string; notes?: string },
  ) => request<RuleDocSummary>('PATCH', `/api/rule-documents/${doc.id}`, change, doc.version),
  confirmRuleDocument: (doc: { id: number; version: number }) =>
    request<RuleDocSummary>('POST', `/api/rule-documents/${doc.id}/confirm`, undefined, doc.version),
  retireRuleDocument: (doc: { id: number; version: number }) =>
    request<RuleDocSummary>('POST', `/api/rule-documents/${doc.id}/retire`, undefined, doc.version),
  deleteRuleDocument: (docId: number) => request<void>('DELETE', `/api/rule-documents/${docId}`),
  updateClause: (
    clause: { id: number; version: number },
    change: { label?: string; title?: string; text?: string; reviewed?: boolean },
  ) => request<ClauseView>('PATCH', `/api/clauses/${clause.id}`, change, clause.version),
  addClause: (docId: number, input: { afterClauseId?: number; kind?: ClauseKind; label: string; title?: string; text: string }) =>
    request<ClauseView>('POST', `/api/rule-documents/${docId}/clauses`, input),
  deleteClause: (clauseId: number) => request<void>('DELETE', `/api/clauses/${clauseId}`),
  reviewClauses: (docId: number, clauseIds: number[]) =>
    request<{ reviewed: number }>('POST', `/api/rule-documents/${docId}/clauses/review`, { clauseIds }),

  /** 카드 하나의 규정 확인 표시. 확인 대상이 아니면 null(204) */
  taskRuleCheck: (taskId: number) => request<RuleCheck | undefined>('GET', `/api/tasks/${taskId}/rule-check`)
    .then((value) => value ?? null),
  expenseReviews: (orgId: number) => request<ExpenseReviewSummary[]>('GET', `/api/orgs/${orgId}/expense-reviews`),
  expenseReview: (reviewId: number) => request<ExpenseReviewView>('GET', `/api/expense-reviews/${reviewId}`),
  createExpenseReview: (input: ExpenseReviewInput) => request<ExpenseReviewView>('POST', '/api/expense-reviews', input),
  /** AI 안내 다시 받기. 이미 대기·진행 중이면 그 작업이 돌아온다. */
  requestExpenseGuidance: (reviewId: number) => request<Job>('POST', `/api/expense-reviews/${reviewId}/guidance`),
  /** 검토 완료(회장). "규정에 맞다"는 뜻이 아니라 안내와 조항 원문을 확인했다는 표시다. */
  completeExpenseReview: (review: { id: number; version: number }) =>
    request<ExpenseReviewView>('POST', `/api/expense-reviews/${review.id}/review`, undefined, review.version),
  checkExpenseItem: (item: { id: number; version: number }, checked: boolean) =>
    request<ExpenseChecklistItem>('PATCH', `/api/expense-review-items/${item.id}`, { checked }, item.version),
}
