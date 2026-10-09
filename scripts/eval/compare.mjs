#!/usr/bin/env node
// 기준 답안(gold)과 AI 결과를 결정·업무 단위로 비교해 원자료 표와 개수를 출력한다.
//
// 사용법:
//   node scripts/eval/compare.mjs <기준 답안.json> <AI 결과.json> [--out 결과.md]
//
// AI 결과는 두 형식 중 하나:
//   1) AI 출력 스키마({ agendas: [...] }) — 모델 응답 원문
//   2) GET /api/meetings/{id}/proposals 응답 — 서버 검증을 거친 후보(담당·기한은 서버가 남긴 값)
//
// 짝짓기: 근거 발언 ID 겹침(Jaccard)과 요약 문장 2글자 조각 유사도를 섞은 점수로, 점수 높은 쌍부터 하나씩 짝짓는다.
// 점수가 기준(0.3)보다 낮으면 짝이 아니다. 짝지은 결과는 사람이 표를 보고 다시 확인한다(숫자보다 표가 중요).
import { readFileSync, writeFileSync } from 'node:fs'

const MATCH_THRESHOLD = 0.3

function usage() {
  console.error('사용법: node scripts/eval/compare.mjs <기준 답안.json> <AI 결과.json> [--out 결과.md]')
  process.exit(2)
}

const args = process.argv.slice(2)
if (args.length < 2) usage()
const outIndex = args.indexOf('--out')
const outPath = outIndex >= 0 ? args[outIndex + 1] : null

const gold = normalize(JSON.parse(readFileSync(args[0], 'utf8')))
const result = normalize(JSON.parse(readFileSync(args[1], 'utf8')))

/** 두 형식을 { summary, status, ids, tasks: [{ title, owner, due, ids }] } 목록으로 맞춘다. */
function normalize(doc) {
  if (Array.isArray(doc.agendas)) {
    return doc.agendas.flatMap((agenda) =>
      agenda.decisions.map((d) => ({
        agenda: agenda.agenda,
        summary: d.summary,
        status: d.decision_status,
        ids: d.source_segment_ids ?? [],
        tasks: (d.tasks ?? []).map((t) => ({
          title: t.title,
          owner: t.owner_department || null,
          due: t.due_date || null,
          ids: t.source_segment_ids ?? [],
        })),
      })),
    )
  }
  if (Array.isArray(doc.decisions)) {
    return doc.decisions.map((d) => ({
      agenda: d.agenda,
      summary: d.summary,
      status: d.aiStatus,
      ids: (d.evidence ?? []).map((s) => s.label),
      tasks: (d.tasks ?? []).map((t) => ({
        title: t.title,
        owner: t.departmentName ?? null,
        due: t.dueDate ?? null,
        ids: (t.evidence ?? []).map((s) => s.label),
      })),
    }))
  }
  throw new Error('알 수 없는 형식입니다. { agendas } 또는 proposals 응답이어야 합니다.')
}

function bigrams(text) {
  const s = (text ?? '').replace(/[\s\p{P}]/gu, '')
  const set = new Set()
  for (let i = 0; i < s.length - 1; i++) set.add(s.slice(i, i + 2))
  return set
}

function overlap(a, b) {
  const A = a instanceof Set ? a : new Set(a)
  const B = b instanceof Set ? b : new Set(b)
  if (A.size === 0 && B.size === 0) return 0
  let common = 0
  for (const x of A) if (B.has(x)) common++
  return common / (A.size + B.size - common)
}

function score(goldItem, resultItem, textKey) {
  return 0.6 * overlap(goldItem.ids, resultItem.ids) + 0.4 * overlap(bigrams(goldItem[textKey]), bigrams(resultItem[textKey]))
}

/** 점수 높은 쌍부터 짝짓는다. 반환: { pairs: [[g, r, score]], missing: [g], extra: [r] } */
function match(goldItems, resultItems, textKey) {
  const candidates = []
  goldItems.forEach((g, gi) =>
    resultItems.forEach((r, ri) => candidates.push([gi, ri, score(g, r, textKey)])),
  )
  candidates.sort((a, b) => b[2] - a[2])
  const usedG = new Set()
  const usedR = new Set()
  const pairs = []
  for (const [gi, ri, s] of candidates) {
    if (s < MATCH_THRESHOLD || usedG.has(gi) || usedR.has(ri)) continue
    usedG.add(gi)
    usedR.add(ri)
    pairs.push([goldItems[gi], resultItems[ri], s])
  }
  return {
    pairs,
    missing: goldItems.filter((_, i) => !usedG.has(i)),
    extra: resultItems.filter((_, i) => !usedR.has(i)),
  }
}

const same = (a, b) => (a ?? '').replace(/\s/g, '') === (b ?? '').replace(/\s/g, '')
const cell = (v) => (v == null || v === '' ? '—' : String(v).replace(/\|/g, '\\|'))

const decisions = match(gold, result, 'summary')
const counts = {
  decision: { correct: 0, missing: decisions.missing.length, extra: decisions.extra.length, statusDiff: 0 },
  task: { correct: 0, missing: 0, extra: 0, ownerError: 0, dueError: 0 },
}

const decisionRows = []
const taskRows = []

for (const [g, r, s] of decisions.pairs) {
  counts.decision.correct++
  const statusOk = g.status === r.status
  if (!statusOk) counts.decision.statusDiff++
  decisionRows.push([g.summary, r.summary, '맞음', s.toFixed(2), `${g.status} / ${r.status}${statusOk ? '' : ' ✗'}`])

  const tasks = match(g.tasks, r.tasks, 'title')
  for (const [gt, rt, ts] of tasks.pairs) {
    counts.task.correct++
    const ownerOk = same(gt.owner, rt.owner)
    const dueOk = same(gt.due, rt.due)
    if (!ownerOk) counts.task.ownerError++
    if (!dueOk) counts.task.dueError++
    taskRows.push([gt.title, rt.title, '맞음', ts.toFixed(2),
      `${cell(gt.owner)} / ${cell(rt.owner)}${ownerOk ? '' : ' ✗'}`,
      `${cell(gt.due)} / ${cell(rt.due)}${dueOk ? '' : ' ✗'}`])
  }
  for (const gt of tasks.missing) {
    counts.task.missing++
    taskRows.push([gt.title, '—', '누락', '', cell(gt.owner), cell(gt.due)])
  }
  for (const rt of tasks.extra) {
    counts.task.extra++
    taskRows.push(['—', rt.title, '추가', '', cell(rt.owner), cell(rt.due)])
  }
}
for (const g of decisions.missing) {
  decisionRows.push([g.summary, '—', '누락', '', g.status])
  for (const gt of g.tasks) {
    counts.task.missing++
    taskRows.push([gt.title, '—', '누락(결정 누락)', '', cell(gt.owner), cell(gt.due)])
  }
}
for (const r of decisions.extra) {
  decisionRows.push(['—', r.summary, '추가', '', r.status])
  for (const rt of r.tasks) {
    counts.task.extra++
    taskRows.push(['—', rt.title, '추가(결정 추가)', '', cell(rt.owner), cell(rt.due)])
  }
}

const table = (header, rows) =>
  [`| ${header.join(' | ')} |`, `|${header.map(() => '---').join('|')}|`, ...rows.map((r) => `| ${r.map(cell).join(' | ')} |`)].join('\n')

const report = [
  `# 회의 분석 비교`,
  ``,
  `- 기준 답안: ${args[0]}`,
  `- AI 결과: ${args[1]}`,
  `- 짝짓기 기준 점수: ${MATCH_THRESHOLD} (근거 ID 겹침 0.6 + 문장 유사도 0.4)`,
  ``,
  `## 결정`,
  ``,
  table(['기준 답안', 'AI 결과', '판정', '점수', '상태(기준 / AI)'], decisionRows),
  ``,
  `## 업무`,
  ``,
  table(['기준 답안', 'AI 결과', '판정', '점수', '담당(기준 / AI)', '기한(기준 / AI)'], taskRows),
  ``,
  `## 개수`,
  ``,
  `| 단위 | 맞음 | 누락 | 추가 | 상태 다름 | 담당 오류 | 기한 오류 |`,
  `|---|---|---|---|---|---|---|`,
  `| 결정 | ${counts.decision.correct} | ${counts.decision.missing} | ${counts.decision.extra} | ${counts.decision.statusDiff} | — | — |`,
  `| 업무 | ${counts.task.correct} | ${counts.task.missing} | ${counts.task.extra} | — | ${counts.task.ownerError} | ${counts.task.dueError} |`,
  ``,
].join('\n')

console.log(report)
if (outPath) writeFileSync(outPath, report)
