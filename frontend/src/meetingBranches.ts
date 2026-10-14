import type { DecisionView, TaskView } from './api'

export interface DepartmentTask {
  task: TaskView
  /** 업무가 나온 결정(승인 여부 표시와 검토 창 열기에 쓴다) */
  decision: DecisionView
}

export interface DepartmentGroup {
  departmentId: number | null
  name: string
  tasks: DepartmentTask[]
}

/**
 * 회의 전체 업무를 담당 부서별로 묶는다(후보에서 뺀 업무 제외).
 * 등록된 부서 순서대로 놓고, 목록에 없는 부서(없앤 부서 등)는 그 뒤, 담당이 없는 업무는 "담당 미정" 하나로 맨 끝에 모은다.
 * AI 추천 부서는 확정 담당이 아니므로 미정에 남긴다. 회장이 담당을 바꾸면 다시 불러온 데이터로 다시 묶인다.
 */
export function departmentGroups(decisions: DecisionView[], departments: { id: number; name: string }[]): DepartmentGroup[] {
  const groups = new Map<number | null, DepartmentGroup>()
  for (const decision of decisions) {
    for (const task of decision.tasks) {
      if (task.status === 'CANCELLED') continue
      let group = groups.get(task.departmentId)
      if (!group) {
        const name = task.departmentId === null ? '담당 미정' : task.departmentName ?? '담당 미정'
        group = { departmentId: task.departmentId, name, tasks: [] }
        groups.set(task.departmentId, group)
      }
      group.tasks.push({ task, decision })
    }
  }
  const order = new Map(departments.map((d, i) => [d.id, i]))
  const rank = (g: DepartmentGroup) =>
    g.departmentId === null ? Number.MAX_SAFE_INTEGER : order.get(g.departmentId) ?? departments.length
  return [...groups.values()].sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name, 'ko'))
}

/** 살아 있는 업무가 하나도 없는 결정(취소된 결정 제외). 부서별 화면에는 업무로 나오지 않으므로 따로 모아 검토하게 한다. */
export function decisionsWithoutTasks(decisions: DecisionView[]): DecisionView[] {
  return decisions.filter((d) => d.status !== 'CANCELLED' && d.tasks.every((t) => t.status === 'CANCELLED'))
}
