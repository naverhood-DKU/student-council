// 서버 API 호출 모음. 토큰은 탭을 닫으면 사라지는 sessionStorage 에만 둔다.

export type OrgType = 'STUDENT_UNION' | 'COLLEGE' | 'DEPARTMENT'
export type MembershipRole = 'ORG_ADMIN' | 'MEETING_CHAIR' | 'DEPT_MEMBER' | 'FINANCE_REVIEWER'
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
  departmentId: number | null
  departmentName: string | null
}

export interface Me {
  id: number
  email: string
  name: string
  memberships: MembershipView[]
}

export interface OrgDetail {
  id: number
  name: string
  type: OrgType
  parentId: number | null
  term: string
  myRoles: MembershipRole[]
  myDepartmentIds: number[]
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

export interface ChildOrg {
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
  addTask: (decisionId: number, task: NewTask) =>
    request<TaskView>('POST', `/api/decisions/${decisionId}/tasks`, task),
  approvedDecisions: (orgId: number) => request<DecisionOption[]>('GET', `/api/orgs/${orgId}/decisions`),

  children: (orgId: number) => request<ChildOrg[]>('GET', `/api/orgs/${orgId}/children`),
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
