#!/usr/bin/env node
// 지출 절차 안내 평가: 사람이 정해 둔 사례(cases.json)를 실행 중인 서버에 요청해 조항이 맞게 나오는지 확인한다.
//
// 사용법:
//   node scripts/eval/expense/run.mjs --org <그룹 id> --login <회장 아이디> --password <비밀번호> [--register] [--wait] [--out report.md]
//   (비밀번호는 EVAL_PASSWORD 환경변수로도 넘길 수 있다. 기본 주소는 http://localhost:8080, --base-url 로 바꾼다)
//
//   --register  rules-sample.txt 를 규정으로 올려 모든 조항을 검수 완료로 표시하고 확정한다.
//               (평가용 그룹에서만 쓴다. 검수 표시를 자동으로 하는 것은 이 평가용 샘플 규정에 한정한다.)
//   --wait      후보가 있는 사례는 AI 안내 작업이 끝날 때까지 기다려 근거(인용)를 확인한다. 서버가 AI 를 쓸 수 있어야 한다.
//
// 확인하는 것
//   1. 정답 조항(expectLabels)이 후보에 있는가, 나오면 안 되는 조항(forbidLabels)이 없는가
//   2. 후보가 없어야 하는 사례는 NEEDS_CHECK 인가
//   3. 화면에 보이는 후보 조항 원문이 DB 원문과 같은가(서버가 준 text 를 규정 파일의 조항 줄과 비교)
//   4. (--wait) AI 안내의 모든 체크리스트 항목: 근거 조항이 후보 안에 있고 인용 구절이 그 조항 원문에 그대로 있는가
//   5. 어느 결과에도 "문제없음"이 없는가
//
// 외부 AI 를 실제로 부르는 평가는 서버에 키가 있을 때만 의미가 있다. fake-ai 프로필이면 파이프라인 확인이다.
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : fallback
}
const flag = (name) => process.argv.includes(`--${name}`)

const baseUrl = arg('base-url', 'http://localhost:8080').replace(/\/$/, '')
const orgId = Number(arg('org'))
const login = arg('login')
const password = arg('password', process.env.EVAL_PASSWORD)
const outPath = arg('out')
if (!orgId || !login || !password) {
  console.error('사용법: node scripts/eval/expense/run.mjs --org <그룹 id> --login <회장 아이디> --password <비밀번호> [--register] [--wait] [--out report.md]')
  process.exit(2)
}

const config = JSON.parse(readFileSync(join(here, 'cases.json'), 'utf8'))
const rulesText = readFileSync(join(here, config.rules), 'utf8').replace(/\r\n/g, '\n')

let token = null

async function call(method, path, body, ifMatch) {
  const headers = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  let payload
  if (body instanceof FormData) payload = body
  else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  if (ifMatch !== undefined) headers['If-Match'] = `"${ifMatch}"`
  const response = await fetch(baseUrl + path, { method, headers, body: payload })
  const text = await response.text()
  const json = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(`${method} ${path} → ${response.status} ${json?.code ?? ''} ${json?.message ?? ''}`)
  return json
}

/** 공백 차이만 무시하려고 모든 공백을 없앤 NFC 글자 */
const squash = (s) => s.normalize('NFC').replace(/[\s ]+/g, '')
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function register() {
  const form = new FormData()
  const title = rulesText.split('\n')[0].trim()
  form.append('title', title)
  form.append('issuer', '평가용 학생회')
  form.append('effectiveDate', config.effectiveDate)
  form.append('docVersion', config.docVersion)
  form.append('text', rulesText)
  const doc = await call('POST', `/api/orgs/${orgId}/rule-documents`, form)
  const ids = doc.clauses
    .filter((c) => ['ARTICLE', 'EXTRA', 'SUPPLEMENTARY'].includes(c.kind))
    .map((c) => c.id)
  await call('POST', `/api/rule-documents/${doc.summary.id}/clauses/review`, { clauseIds: ids })
  const fresh = await call('GET', `/api/rule-documents/${doc.summary.id}`)
  await call('POST', `/api/rule-documents/${doc.summary.id}/confirm`, undefined, fresh.summary.version)
  console.error(`규정 등록·확정: 문서 ${doc.summary.id}, 조항 ${ids.length}개`)
}

/** 규정 파일에서 "제n조" 줄을 라벨 → 원문으로 만든다(화면 원문과 비교하는 기준). */
function articleLines() {
  const map = new Map()
  for (const line of rulesText.split('\n')) {
    const m = line.match(/^(제\d+조)/)
    if (m) map.set(m[1], line.trim())
  }
  return map
}

async function waitForJob(reviewId, timeoutMs = 120_000) {
  const until = Date.now() + timeoutMs
  for (;;) {
    const view = await call('GET', `/api/expense-reviews/${reviewId}`)
    const status = view.job?.status
    if (!status || (status !== 'QUEUED' && status !== 'RUNNING')) return view
    if (Date.now() > until) return view
    await sleep(2000)
  }
}

function evaluate(testCase, view, lines) {
  const problems = []
  const labels = view.candidates.map((c) => c.label)
  const expectStatus = testCase.expectStatus
  const reviewStatuses = ['NEEDS_CHECK', 'CANDIDATES_FOUND', 'PROCEDURE_FOUND', 'POSSIBLE_CONFLICT']
  if (expectStatus === 'NEEDS_CHECK' && view.status !== 'NEEDS_CHECK') problems.push(`상태가 ${view.status} (확인 필요여야 함)`)
  if (expectStatus === 'CANDIDATES_FOUND' && !reviewStatuses.includes(view.status)) problems.push(`상태가 ${view.status}`)
  for (const label of testCase.expectLabels ?? []) {
    if (!labels.includes(label)) problems.push(`정답 조항 ${label} 가 후보에 없음`)
  }
  for (const label of testCase.forbidLabels ?? []) {
    if (labels.includes(label)) problems.push(`나오면 안 되는 조항 ${label} 가 후보에 있음`)
  }
  if (expectStatus === 'NEEDS_CHECK' && view.candidates.length > 0) problems.push('후보가 있음')
  if (view.status === 'NEEDS_CHECK' && !view.notices.some((n) => n.includes('문제가 없다는 뜻이 아닙니다') || n.includes('확정된 규정이 없습니다'))) {
    problems.push('확인 필요인데 "문제가 없다는 뜻이 아닙니다" 알림이 없음')
  }
  for (const candidate of view.candidates) {
    const expected = lines.get(candidate.label)
    if (expected && squash(candidate.text) !== squash(expected)) problems.push(`${candidate.label} 원문이 규정 파일과 다름`)
  }
  if (JSON.stringify(view).includes('문제없음')) problems.push('결과에 "문제없음"이 있음')
  // AI 안내 근거 확인(서버가 이미 검증하지만, 화면에 나온 값으로 다시 확인한다)
  for (const item of view.checklist ?? []) {
    for (const ref of item.clauses) {
      const clause = view.candidates.find((c) => c.clauseId === ref.clauseId)
      if (!clause) problems.push(`체크리스트 근거 조항 ${ref.label} 가 후보 밖`)
    }
    const cited = item.clauses.map((r) => view.candidates.find((c) => c.clauseId === r.clauseId)).filter(Boolean)
    if (!cited.some((c) => squash(c.text).includes(squash(item.quote)))) problems.push(`인용 구절이 근거 조항 원문에 없음: ${item.quote.slice(0, 20)}`)
  }
  return { labels, problems }
}

async function main() {
  const loginResult = await call('POST', '/api/auth/login', { email: login, password })
  token = loginResult.accessToken
  if (flag('register')) await register()

  const lines = articleLines()
  const rows = []
  for (const testCase of config.cases) {
    const body = { orgId, item: testCase.item }
    if (testCase.amount) body.amount = testCase.amount
    if (testCase.keywords) body.keywords = testCase.keywords
    let view = await call('POST', '/api/expense-reviews', body)
    if (flag('wait') && view.candidates.length > 0) view = await waitForJob(view.id)
    const { labels, problems } = evaluate(testCase, view, lines)
    rows.push({ testCase, view, labels, problems })
  }

  const passed = rows.filter((r) => r.problems.length === 0).length
  const out = []
  out.push('# 지출 절차 안내 평가')
  out.push('')
  out.push(`서버 ${baseUrl}, 그룹 ${orgId}, 사례 ${rows.length}건 중 문제 없음 ${passed}건${flag('wait') ? ' (AI 안내 포함)' : ' (후보 조항 검색만)'}`)
  out.push('')
  out.push('| 사례 | 항목 | 정답 조항 | 나온 후보(관련도 순) | 상태 | AI 안내 | 문제 |')
  out.push('|---|---|---|---|---|---|---|')
  for (const { testCase, view, labels, problems } of rows) {
    const ai = view.guidance
      ? `${view.guidance.outcome}, 체크 ${view.checklist.length}개, 제외 ${view.guidance.droppedCount}개`
      : view.job ? view.job.status + (view.job.lastError ? `(${view.job.lastError})` : '') : '-'
    out.push(`| ${testCase.id} | ${testCase.item}${testCase.amount ? ` / ${testCase.amount}원` : ''} | ${(testCase.expectLabels ?? []).join(', ') || '-'} | ${labels.join(', ') || '-'} | ${view.status} | ${ai} | ${problems.join('; ') || '없음'} |`)
  }
  out.push('')
  out.push('표를 보고 사람이 다시 확인합니다. 후보는 키워드로 찾은 것이라 정답 조항 외의 조항이 함께 나올 수 있습니다.')
  const report = out.join('\n') + '\n'
  if (outPath) writeFileSync(outPath, report)
  process.stdout.write(report)
  process.exit(passed === rows.length ? 0 : 1)
}

main().catch((error) => {
  console.error(error.message)
  process.exit(2)
})
