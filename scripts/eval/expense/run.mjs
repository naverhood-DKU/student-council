#!/usr/bin/env node
// 지출 절차 안내 평가: 사람이 정해 둔 사례를 실행 중인 서버에 요청해 조항·금지·제한이 맞게 나오는지 확인한다.
//
// 사용법:
//   node scripts/eval/expense/run.mjs --org <그룹 id> --login <회장 아이디> --password <비밀번호> \
//        [--set real|synthetic] [--register] [--wait] [--out report.md] [--base-url http://localhost:8080]
//   (비밀번호는 EVAL_PASSWORD 환경변수로도 넘길 수 있다)
//
//   --set       real(기본): rules/ 의 실제 규정 PDF 3개와 cases.json / synthetic: 가상 재정 세칙과 synthetic/cases.json
//   --register  규정 파일을 올려 모든 조항을 검수 완료로 표시하고 확정한다. 평가용 그룹에서만 쓴다.
//               (사람이 원문을 대조해야 하는 검수를 자동으로 건너뛰는 것이므로 운영 그룹에서는 쓰지 않는다)
//   --wait      후보가 있는 사례는 AI 안내 작업이 끝날 때까지 기다려 AI 결과도 확인한다. 서버가 AI 를 쓸 수 있어야 한다.
//
// 확인하는 것
//   [검색] 정답 조항(expectCandidates)이 후보에 있는가
//   [원문] 후보 조항 원문이 DB 값과 같은가는 서버 테스트가 보증한다. 여기서는 "문제없음"이 없는지만 본다.
//   [AI]   --wait 일 때:
//          - 기대한 금지·제한(expectRestriction)이 나왔는가
//          - 기대한 승인·증빙(expectChecklist)이 나왔는가
//          - 승인·증빙 근거로 나오면 안 되는 조항(forbidChecklist)이 없는가
//          - 해당 조항이 없는 지출(expectNoGuidanceItems)에서 아무것도 확정되지 않았는가
//          - 모든 체크리스트 인용이 근거 조항 원문 안에 있는가
//
// 실제 모델로 돌릴 때는 서버에 CLOVA 키가 있어야 한다. 결과는 한 번 실행한 것이라 같은 조건에서도 달라질 수 있다.
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
const setName = arg('set', 'real')
if (!orgId || !login || !password || !['real', 'synthetic'].includes(setName)) {
  console.error('사용법: node scripts/eval/expense/run.mjs --org <그룹 id> --login <회장 아이디> --password <비밀번호> [--set real|synthetic] [--register] [--wait] [--out report.md]')
  process.exit(2)
}

const setDir = setName === 'real' ? here : join(here, 'synthetic')
const config = JSON.parse(readFileSync(join(setDir, 'cases.json'), 'utf8'))

/** 두 세트의 형식을 같은 모양({ documents: [{key,title,file|text,...}], cases })으로 맞춘다. */
function normalize() {
  if (config.documents) return { documents: config.documents, cases: config.cases }
  const text = readFileSync(join(setDir, config.rules), 'utf8').replace(/\r\n/g, '\n')
  const documents = [{
    key: 'rules',
    title: text.split('\n')[0].trim(),
    text,
    effectiveDate: config.effectiveDate,
    docVersion: config.docVersion,
  }]
  const cases = config.cases.map((c) => ({
    ...c,
    expectCandidates: (c.expectLabels ?? []).map((label) => ({ doc: 'rules', label })),
    forbidChecklist: (c.forbidLabels ?? []).map((label) => ({ doc: 'rules', label })),
  }))
  return { documents, cases }
}
const { documents, cases } = normalize()
const titleOf = (key) => documents.find((d) => d.key === key)?.title

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

/** 글자와 숫자만 남긴 비교용 글(공백·따옴표·문장부호·동그라미 숫자 차이는 무시). 서버 QuoteMatcher 와 같다. */
const loose = (s) => s.normalize('NFC').replace(/[^\p{L}0-9]+/gu, '')
/** 인용을 문장 끝·번호 머리·동그라미 숫자·말줄임표에서 조각으로 나눈다. */
const fragments = (q) =>
  q
    .split(/(?<=[.!?。])\s+|\s+(?=\d{1,2}\.\s)|[\u2460-\u2473\u2474-\u2487\u24F5-\u24FE]|\.{3}|\u2026/u)
    .map(loose)
    .filter((f) => [...f].length >= 2)
/** 모든 조각이 원문에 순서대로 그대로 있는가 */
function verbatim(clauseText, quote) {
  const hay = loose(clauseText)
  const frags = fragments(quote)
  if (frags.length === 0) return false
  let from = 0
  for (const f of frags) {
    const at = hay.indexOf(f, from)
    if (at < 0) return false
    from = at + f.length
  }
  return true
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function register() {
  for (const doc of documents) {
    const form = new FormData()
    form.append('title', doc.title)
    form.append('issuer', '평가용 학생회')
    form.append('effectiveDate', doc.effectiveDate)
    form.append('docVersion', doc.docVersion)
    if (doc.file) {
      const bytes = readFileSync(join(setDir, doc.file))
      form.append('file', new Blob([bytes], { type: 'application/pdf' }), doc.file.split('/').pop())
    } else {
      form.append('text', doc.text)
    }
    const created = await call('POST', `/api/orgs/${orgId}/rule-documents`, form)
    const ids = created.clauses
      .filter((c) => ['ARTICLE', 'EXTRA', 'SUPPLEMENTARY'].includes(c.kind))
      .map((c) => c.id)
    await call('POST', `/api/rule-documents/${created.summary.id}/clauses/review`, { clauseIds: ids })
    const fresh = await call('GET', `/api/rule-documents/${created.summary.id}`)
    await call('POST', `/api/rule-documents/${created.summary.id}/confirm`, undefined, fresh.summary.version)
    const warnings = created.registrationWarnings.length ? ` / 경고 ${created.registrationWarnings.length}개` : ''
    console.error(`규정 등록·확정: ${doc.title} (문서 ${created.summary.id}, 조항 ${ids.length}개${warnings})`)
  }
}

async function waitForJob(reviewId, timeoutMs = 180_000) {
  const until = Date.now() + timeoutMs
  for (;;) {
    const view = await call('GET', `/api/expense-reviews/${reviewId}`)
    const status = view.job?.status
    if (!status || (status !== 'QUEUED' && status !== 'RUNNING')) return view
    if (Date.now() > until) return view
    await sleep(2000)
  }
}

const same = (c, ref) => c.label === ref.label && c.docTitle === titleOf(ref.doc)

function evaluate(testCase, view) {
  const search = []
  const ai = []
  const soft = []
  const cand = view.candidates
  for (const ref of testCase.expectCandidates ?? []) {
    if (!cand.some((c) => same(c, ref))) search.push(`정답 조항 ${ref.doc} ${ref.label} 가 후보에 없음`)
  }
  for (const ref of testCase.softCandidates ?? []) {
    if (!cand.some((c) => same(c, ref))) soft.push(`(참고) ${ref.doc} ${ref.label} 가 후보에 없음(순위 경계)`)
  }
  if (testCase.expectStatus === 'NEEDS_CHECK' && cand.length > 0) search.push('후보가 없어야 하는데 있음')
  if (view.status === 'NEEDS_CHECK' && !view.notices.some((n) => n.includes('문제가 없다는 뜻이 아닙니다') || n.includes('확정된 규정이 없습니다'))) {
    search.push('확인 필요인데 "문제가 없다는 뜻이 아닙니다" 알림이 없음')
  }
  if (JSON.stringify(view).includes('문제없음')) search.push('결과에 "문제없음"이 있음')

  if (!flag('wait')) return { search, ai, soft }
  const g = view.guidance
  if (cand.length > 0 && !g) {
    ai.push(`AI 안내 없음(작업 ${view.job?.status ?? '-'} ${view.job?.lastError ?? ''})`)
    return { search, ai, soft }
  }
  // 지출 항목과 직접 관련 있어 보이는 금지·제한만 센다(서버가 관련 없어 보이는 것은 따로 표시한다)
  const restrictions = (g?.restrictions ?? []).filter((r) => r.related)
  if (testCase.expectRestriction) {
    const accept = (r) => testCase.expectRestriction === 'ANY' || r.kind === testCase.expectRestriction
    if (!restrictions.some(accept)) {
      ai.push(`기대한 ${testCase.expectRestriction} 이 나오지 않음(상태 ${view.status})`)
    }
  }
  for (const ref of testCase.expectChecklist ?? []) {
    if (!cand.some((c) => same(c, ref))) {
      soft.push(`(참고) ${ref.doc} ${ref.label} 가 후보에 없어 ${ref.kind} 를 확인할 수 없음`)
      continue
    }
    const found = view.checklist.some((i) => i.kind === ref.kind && i.clauses.some((c) => same(c, ref)))
    if (!found) ai.push(`기대한 ${ref.kind} ${ref.doc} ${ref.label} 가 체크리스트에 없음`)
  }
  for (const ref of testCase.forbidChecklist ?? []) {
    const hit = view.checklist.find((i) => i.clauses.some((c) => same(c, ref)))
    if (hit) ai.push(`${ref.doc} ${ref.label} 를 근거로 ${hit.kind} 항목이 나옴: "${hit.text.slice(0, 40)}"`)
  }
  if (testCase.expectNoGuidanceItems && (view.checklist.length > 0 || restrictions.length > 0)) {
    ai.push(`해당 조항이 없는 지출인데 항목이 나옴(체크 ${view.checklist.length}, 금지·제한 ${restrictions.length})`)
  }
  for (const item of view.checklist) {
    const cited = item.clauses.map((r) => cand.find((c) => c.clauseId === r.clauseId)).filter(Boolean)
    if (!cited.some((c) => verbatim(c.text, item.quote))) ai.push(`인용 구절이 근거 조항 원문에 없음: ${item.quote.slice(0, 20)}`)
  }
  return { search, ai, soft }
}

async function main() {
  token = (await call('POST', '/api/auth/login', { email: login, password })).accessToken
  if (flag('register')) await register()

  const rows = []
  for (const testCase of cases) {
    const body = { orgId, item: testCase.item }
    if (testCase.amount) body.amount = testCase.amount
    if (testCase.fundSource) body.fundSource = testCase.fundSource
    if (testCase.keywords) body.keywords = testCase.keywords
    let view = await call('POST', '/api/expense-reviews', body)
    if (flag('wait') && view.candidates.length > 0) view = await waitForJob(view.id)
    rows.push({ testCase, view, ...evaluate(testCase, view) })
  }

  const searchOk = rows.filter((r) => r.search.length === 0).length
  const aiOk = rows.filter((r) => r.ai.length === 0).length
  const out = []
  out.push(`# 지출 절차 안내 평가 (${setName === 'real' ? '실제 규정' : '가상 규정'})`)
  out.push('')
  out.push(`서버 ${baseUrl}, 그룹 ${orgId}, 사례 ${rows.length}건`)
  out.push(`- 후보 검색: ${searchOk} / ${rows.length}건에서 정답 조항이 후보에 있음`)
  if (flag('wait')) out.push(`- AI 안내: ${aiOk} / ${rows.length}건에서 기대한 금지·승인·증빙이 나오고 금지된 근거가 없음`)
  out.push('')
  out.push('| 사례 | 항목 | 상태 | 후보(관련도 순) | AI 안내 | 검색 문제 | AI 문제 |')
  out.push('|---|---|---|---|---|---|---|')
  const short = (title) => (title.includes('감사') ? '감사' : title.includes('총대') ? '총대' : title.includes('학생회칙') ? '회칙' : '')
  for (const { testCase, view, search, ai, soft } of rows) {
    const labels = view.candidates.map((c) => `${short(c.docTitle)}${short(c.docTitle) ? ' ' : ''}${c.label}`).join(', ') || '-'
    const g = view.guidance
    const aiCell = g
      ? `승인·증빙 ${view.checklist.length}, 금지·제한 ${g.restrictions.filter((r) => r.related).length}${g.restrictions.some((r) => !r.related) ? `(+관련 없어 보임 ${g.restrictions.filter((r) => !r.related).length})` : ''}${g.droppedCount ? `, 제외 ${g.droppedCount}` : ''}`
      : view.job ? view.job.status + (view.job.lastError ? `(${view.job.lastError})` : '') : '-'
    out.push(`| ${testCase.id} | ${testCase.item}${testCase.amount ? ` / ${testCase.amount}원` : ''} | ${view.status} | ${labels} | ${aiCell} | ${[...search, ...soft].join('; ') || '없음'} | ${flag('wait') ? ai.join('; ') || '없음' : '-'} |`)
  }
  out.push('')
  out.push('표를 보고 사람이 다시 확인합니다. 후보는 키워드로 찾은 것이라 정답 조항 외의 조항이 함께 나올 수 있습니다.')
  const report = out.join('\n') + '\n'
  if (outPath) writeFileSync(outPath, report)
  process.stdout.write(report)
  const failed = flag('wait') ? rows.filter((r) => r.search.length || r.ai.length).length : rows.filter((r) => r.search.length).length
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error(error.message)
  process.exit(2)
})
