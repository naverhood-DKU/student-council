import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, type BoardTask, type DecisionOption, type Member, type OrgDetail } from '../api'
import { useAuth } from '../auth'
import { isOverdue, positionLabel } from '../labels'
import { errorMessage, isOwner, useLoad, useOrg } from '../useOrg'
import { AddTaskForm } from './TaskForms'
import { TopBar } from './TopBar'

/**
 * 업무보드 첫 화면: 그룹 → 부서 → 사람 트리. 부서를 누르면 그 부서에 맡겨진 업무(DepartmentBoardPage)로 간다.
 * 부서마다 단계별 업무 수, 기한 지난 업무, 나에게 맡겨진 체크리스트 수를 보여 준다.
 */
export function BoardPage() {
  const orgId = Number(useParams().orgId)
  const [org] = useOrg(orgId)

  return (
    <>
      <TopBar />
      <main className="page">
        <p><Link to={`/orgs/${orgId}`} className="muted small">← 대시보드</Link></p>
        {org.status === 'loading' && <p className="page-message">불러오는 중…</p>}
        {org.status === 'error' && <p className="error" role="alert">{org.message}</p>}
        {org.status === 'ready' && <BoardTree org={org.data} />}
      </main>
    </>
  )
}

interface TreeData {
  tasks: BoardTask[]
  members: Member[]
}

interface Counts {
  todo: number
  doing: number
  submitted: number
  done: number
  overdue: number
  myOpenItems: number
}

function count(tasks: BoardTask[], myId: number | null): Counts {
  const open = tasks.filter((t) => t.status !== 'CANCELLED')
  return {
    todo: open.filter((t) => t.status === 'UNASSIGNED' || t.status === 'ASSIGNED').length,
    doing: open.filter((t) => t.status === 'IN_PROGRESS').length,
    submitted: open.filter((t) => t.status === 'SUBMITTED').length,
    done: open.filter((t) => t.status === 'DONE').length,
    overdue: open.filter((t) => isOverdue(t.dueDate, t.status)).length,
    myOpenItems: open.flatMap((t) => t.items).filter((i) => !i.done && i.assigneeUserId === myId).length,
  }
}

function BoardTree({ org }: { org: OrgDetail }) {
  const { state } = useAuth()
  const myId = state.status === 'signed-in' ? state.me.id : null
  const [data, reload] = useLoad<TreeData>(
    async () => ({ tasks: await api.board(org.id), members: await api.members(org.id) }),
    [org.id],
    '업무를 불러오지 못했습니다.',
  )
  const [adding, setAdding] = useState<DecisionOption[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const chair = isOwner(org)

  async function openAdd() {
    setError(null)
    try {
      setAdding(await api.approvedDecisions(org.id))
    } catch (e) {
      setError(errorMessage(e, '결정 목록을 불러오지 못했습니다.'))
    }
  }

  if (data.status === 'loading') return <p className="page-message">불러오는 중…</p>
  if (data.status === 'error') return <p className="error" role="alert">{data.message}</p>
  const { tasks, members } = data.data
  const owners = members.filter((m) => m.role === 'OWNER')
  const unassigned = tasks.filter((t) => t.departmentId == null && t.status !== 'CANCELLED')
  const noDepartment = members.filter((m) => m.role !== 'OWNER' && m.departmentId == null)
  // 없앤 부서가 맡았던 업무(끝난 업무만 남는다)도 따로 모아 보여 준다.
  const activeIds = new Set(org.departments.map((d) => d.id))
  const retired = [...new Map(
    tasks.filter((t) => t.departmentId != null && !activeIds.has(t.departmentId))
      .map((t) => [t.departmentId as number, t.departmentName ?? '없앤 부서']),
  )]

  return (
    <>
      <div className="actions-row between">
        <h2>업무보드</h2>
        {chair && adding == null && (
          <button type="button" className="primary inline" onClick={openAdd}>업무 추가</button>
        )}
      </div>
      <p className="muted small">
        부서를 누르면 그 부서에 맡겨진 업무와 체크리스트가 보입니다. 회장이 승인한 업무만 나옵니다.
      </p>
      {adding != null && (
        <AddTaskForm
          departments={org.departments}
          decisions={adding}
          onDone={() => {
            setAdding(null)
            reload()
          }}
          onCancel={() => setAdding(null)}
        />
      )}
      {error && <p className="error" role="alert">{error}</p>}

      <div className="tree">
        <div className="tree-root card">
          <strong>{org.name}</strong>
          <span className="muted small block">
            회장 {owners.length > 0 ? owners.map((m) => m.name).join(', ') : '없음'}
            {noDepartment.length > 0 && ` · 부서 없는 구성원 ${noDepartment.length}명`}
          </span>
        </div>
        <ul className="tree-children">
          {org.departments.map((d) => {
            const people = members.filter((m) => m.departmentId === d.id)
            return (
              <DepartmentNode
                key={d.id}
                to={`/orgs/${org.id}/board/${d.id}`}
                name={d.name}
                mine={org.myDepartmentIds.includes(d.id)}
                people={people}
                counts={count(tasks.filter((t) => t.departmentId === d.id), myId)}
              />
            )
          })}
          {retired.map(([id, name]) => (
            <DepartmentNode
              key={id}
              to={`/orgs/${org.id}/board/${id}`}
              name={`${name} (없앤 부서)`}
              mine={false}
              people={[]}
              counts={count(tasks.filter((t) => t.departmentId === id), myId)}
            />
          ))}
          {unassigned.length > 0 && (
            <DepartmentNode
              to={`/orgs/${org.id}/board/none`}
              name="담당 미정"
              mine={false}
              people={[]}
              counts={count(unassigned, myId)}
            />
          )}
        </ul>
      </div>
    </>
  )
}

function DepartmentNode({ to, name, mine, people, counts }: {
  to: string
  name: string
  mine: boolean
  people: Member[]
  counts: Counts
}) {
  const total = counts.todo + counts.doing + counts.submitted + counts.done
  const donePercent = total === 0 ? 0 : Math.round((counts.done / total) * 100)
  return (
    <li className="tree-node">
      <Link to={to} className={`card link-card tree-card${mine ? ' mine' : ''}`}>
        <span className="card-title-row">
          <strong>{name}</strong>
          <span className="chips">
            {mine && <span className="chip">내 부서</span>}
            {counts.myOpenItems > 0 && <span className="chip mine-chip">나에게 맡겨진 일 {counts.myOpenItems}</span>}
          </span>
        </span>
        <span className="tree-stats small">
          할 일 {counts.todo} · 진행 {counts.doing} · 제출 {counts.submitted} · 완료 {counts.done}
          {counts.overdue > 0 && <span className="chip warn inline-chip">기한 지남 {counts.overdue}</span>}
        </span>
        {total > 0 && (
          <span className="tally-bar" role="img" aria-label={`완료 ${counts.done} / 전체 ${total}`}>
            <span className="tally-bar-fill" style={{ width: `${donePercent}%` }} />
          </span>
        )}
      </Link>
      {people.length > 0 && (
        <ul className="tree-leaves">
          {people.map((m) => (
            <li key={m.membershipId} className="small">
              {m.name} <span className="muted">{positionLabel(m)}</span>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}
