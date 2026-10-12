import test from 'node:test'
import assert from 'node:assert/strict'
import { departmentBranches } from '../src/meetingBranches.ts'

const task = (id, extra = {}) => ({ id, departmentId: null, departmentName: null, status: 'PROPOSED', ...extra })

test('AI 추천 부서를 확정 담당 부서로 표시하지 않는다', () => {
  const groups = departmentBranches([task(1, { suggestedDepartmentId: 7, suggestedDepartmentName: '홍보부' })])
  assert.equal(groups[0]?.name, '담당 미정')
  assert.equal(groups[0]?.departmentId, null)
})

test('같은 이름이어도 다른 부서는 별개 가지로 유지한다', () => {
  const groups = departmentBranches([task(1, { departmentId: 1, departmentName: '기획부' }), task(2, { departmentId: 2, departmentName: '기획부' }), task(3, { departmentId: 1, departmentName: '기획부' })])
  assert.equal(groups.length, 2)
  assert.deepEqual(groups[0].tasks.map(t => t.id), [1, 3])
})

test('후보에서 제외한 업무는 배분 가지에 나타나지 않는다', () => {
  const groups = departmentBranches([task(1, { status: 'CANCELLED' }), task(2)])
  assert.deepEqual(groups.flatMap(g => g.tasks.map(t => t.id)), [2])
})
