import test from 'node:test'
import assert from 'node:assert/strict'
import { decisionsWithoutTasks, departmentGroups } from '../src/meetingBranches.ts'

const task = (id, extra = {}) => ({ id, departmentId: null, departmentName: null, status: 'PROPOSED', ...extra })
const decision = (id, tasks, extra = {}) => ({ id, status: 'PROPOSED', tasks, ...extra })
const departments = [{ id: 1, name: '기획부' }, { id: 2, name: '홍보부' }, { id: 3, name: '재정부' }]

test('여러 결정의 업무를 부서별로 모으고, 등록된 부서 순서 뒤에 담당 미정을 둔다', () => {
  const groups = departmentGroups([
    decision(10, [task(1, { departmentId: 3, departmentName: '재정부' }), task(2)]),
    decision(11, [task(3, { departmentId: 1, departmentName: '기획부' }), task(4, { departmentId: 3, departmentName: '재정부' })]),
  ], departments)
  assert.deepEqual(groups.map((g) => g.name), ['기획부', '재정부', '담당 미정'])
  assert.deepEqual(groups[1].tasks.map((t) => [t.task.id, t.decision.id]), [[1, 10], [4, 11]])
  assert.deepEqual(groups[2].tasks.map((t) => t.task.id), [2])
})

test('AI 추천 부서는 확정 담당이 아니므로 담당 미정에 남긴다', () => {
  const groups = departmentGroups([decision(10, [task(1, { suggestedDepartmentId: 2, suggestedDepartmentName: '홍보부' })])], departments)
  assert.equal(groups.length, 1)
  assert.equal(groups[0].name, '담당 미정')
  assert.equal(groups[0].departmentId, null)
})

test('목록에 없는 부서(없앤 부서)는 등록된 부서 뒤, 미정 앞에 둔다', () => {
  const groups = departmentGroups([decision(10, [task(1), task(2, { departmentId: 9, departmentName: '옛 사무부' }), task(3, { departmentId: 2, departmentName: '홍보부' })])], departments)
  assert.deepEqual(groups.map((g) => g.name), ['홍보부', '옛 사무부', '담당 미정'])
})

test('후보에서 뺀 업무는 나오지 않는다', () => {
  const groups = departmentGroups([decision(10, [task(1, { status: 'CANCELLED', departmentId: 1 }), task(2, { departmentId: 1, departmentName: '기획부' })])], departments)
  assert.deepEqual(groups.flatMap((g) => g.tasks.map((t) => t.task.id)), [2])
})

test('살아 있는 업무가 없는 결정만 따로 모은다(취소된 결정 제외)', () => {
  const list = decisionsWithoutTasks([
    decision(10, [task(1)]),
    decision(11, []),
    decision(12, [task(2, { status: 'CANCELLED' })]),
    decision(13, [], { status: 'CANCELLED' }),
  ])
  assert.deepEqual(list.map((d) => d.id), [11, 12])
})
