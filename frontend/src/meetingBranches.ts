import type { TaskView } from './api'

export interface DepartmentBranch {
  departmentId: number | null
  name: string
  tasks: TaskView[]
}

export function departmentBranches(tasks: TaskView[]): DepartmentBranch[] {
  const groups = new Map<number | null, DepartmentBranch>()
  for (const task of tasks) {
    if (task.status === 'CANCELLED') continue
    let branch = groups.get(task.departmentId)
    if (!branch) {
      branch = { departmentId: task.departmentId, name: task.departmentName ?? '담당 미정', tasks: [] }
      groups.set(task.departmentId, branch)
    }
    branch.tasks.push(task)
  }
  return [...groups.values()]
}
