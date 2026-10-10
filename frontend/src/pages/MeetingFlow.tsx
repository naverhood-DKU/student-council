import type { Proposals } from '../api'
import { departmentBranches } from '../meetingBranches'
import { DECISION_STATUS_LABEL, TASK_STATUS_LABEL } from '../labels'

export type FlowSelection = { kind: 'decision' | 'task'; id: number; segmentIds: number[] } | null

export function FlowIcon({ kind }: { kind: 'meeting' | 'branch' | 'task' | 'audio' }) {
  return <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind === 'meeting' && <><rect x="5" y="3" width="14" height="18" rx="2" /><path d="M9 8h6M9 12h6M9 16h4" /></>}
    {kind === 'branch' && <><circle cx="5" cy="12" r="2" /><circle cx="19" cy="5" r="2" /><circle cx="19" cy="19" r="2" /><path d="M7 12h4a3 3 0 0 0 3-3V8a3 3 0 0 1 3-3M11 12a3 3 0 0 1 3 3v1a3 3 0 0 0 3 3" /></>}
    {kind === 'task' && <><rect x="4" y="4" width="16" height="16" rx="3" /><path d="m8 12 3 3 5-6" /></>}
    {kind === 'audio' && <><path d="M4 10v4M8 6v12M12 3v18M16 7v10M20 10v4" /></>}
  </svg>
}

export function MeetingFlow({ data, selection, onSelect, onOpenTranscript }: {
  data: Proposals
  selection: FlowSelection
  onSelect: (selection: FlowSelection) => void
  onOpenTranscript: () => void
}) {
  const activeTasks = data.decisions.flatMap(d => d.tasks).filter(t => t.status !== 'CANCELLED')
  const departments = new Set(activeTasks.filter(t => t.departmentId !== null).map(t => t.departmentId))
  const isSelected = (kind: 'decision' | 'task', id: number) => selection?.kind === kind && selection.id === id

  return <section className="meeting-map" aria-label="회의에서 부서 업무로 이어지는 흐름">
    <header className="map-heading">
      <div><span className="eyebrow">회의에서 나온 일을 한눈에</span><h3>한 번의 회의, 부서별로 이어지는 업무</h3></div>
      <span className="map-legend"><i /> 승인 전 후보 <i className="confirmed-dot" /> 승인된 결정</span>
    </header>
    <div className="map-columns" aria-hidden="true"><span>출발점 · 회의록</span><span>회의에서 정한 결정</span><span>담당 부서와 할 일</span></div>
    <div className="flow-canvas">
      <div className="flow-source">
        <button type="button" className="source-node" onClick={onOpenTranscript}>
          <span className="node-icon"><FlowIcon kind="meeting" /></span>
          <span className="eyebrow">모든 업무의 출발점</span>
          <strong>{data.meeting.title}</strong>
          <span className="source-meta">발언 {data.segments.length}개 · 회의록 v{data.meeting.transcriptVersion}</span>
          <span className="source-counts"><b>{data.decisions.length}<small>결정</small></b><b>{departments.size}<small>담당 부서</small></b><b>{activeTasks.length}<small>할 일</small></b></span>
          <span className="source-link">회의록 원문 열기 ↗</span>
        </button>
        <p className="source-help">결정과 업무를 누르면<br />근거 발언까지 연결됩니다.</p>
      </div>
      <div className="flow-branches">
        {data.decisions.length === 0 && <div className="flow-empty"><FlowIcon kind="branch" /><strong>아직 이어진 업무가 없습니다</strong><p>결정사항이 없는 회의일 수 있어요. 원문을 확인하거나 업무를 직접 등록하세요.</p></div>}
        {data.decisions.map((decision, index) => {
          const branches = departmentBranches(decision.tasks)
          return <div className="decision-branch" key={decision.id} style={{ animationDelay: `${Math.min(index, 5) * 70}ms` }}>
            <button type="button" className={`decision-node ${decision.status === 'APPROVED' ? 'confirmed' : ''} ${isSelected('decision', decision.id) ? 'active-node' : ''}`}
              onClick={() => onSelect({ kind: 'decision', id: decision.id, segmentIds: decision.sourceSegmentIds })}
              aria-pressed={isSelected('decision', decision.id)} aria-controls="meeting-inspector">
              <span className="node-topline"><span className="node-number">{String(index + 1).padStart(2, '0')}</span><span className={`chip status-${decision.status.toLowerCase()}`}>{DECISION_STATUS_LABEL[decision.status]}</span></span>
              <span className="eyebrow">{decision.agenda}</span><strong>{decision.summary}</strong>
              <span className="node-foot">{branches.reduce((n, b) => n + b.tasks.length, 0)}개 업무 · 근거 확인 →</span>
            </button>
            <div className="department-branches">
              {branches.length === 0 && <button type="button" className="no-task-node" onClick={() => onSelect({ kind: 'decision', id: decision.id, segmentIds: decision.sourceSegmentIds })}>연결된 업무 없음 · 결정 검토</button>}
              {branches.map(branch => <div className={`department-node ${branch.departmentId === null ? 'unassigned-node' : ''}`} key={branch.departmentId ?? 'unassigned'}>
                <div className="department-node-head"><FlowIcon kind="branch" /><strong>{branch.name}</strong><span>{branch.tasks.length}건</span></div>
                {branch.tasks.map(task => <button type="button" key={task.id} className={`flow-task ${isSelected('task', task.id) ? 'active-node' : ''}`}
                  aria-pressed={isSelected('task', task.id)} aria-controls="meeting-inspector"
                  onClick={() => onSelect({ kind: 'task', id: task.id, segmentIds: task.sourceSegmentIds })}>
                  <strong>{task.title}</strong>
                  <span className="task-node-meta"><span>{TASK_STATUS_LABEL[task.status]}</span><span>기한 {task.dueDate ?? '미정'}</span></span>
                  {task.departmentId === null && task.suggestedDepartmentName && <span className="suggested-note">AI 추천: {task.suggestedDepartmentName} · 확인 필요</span>}
                </button>)}
              </div>)}
            </div>
          </div>
        })}
      </div>
    </div>
    <footer className="map-footer"><span>AI가 제안한 업무는 승인 후 업무보드에 반영됩니다.</span><span>담당·기한이 없는 내용은 미정으로 남깁니다.</span></footer>
  </section>
}
