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
}
