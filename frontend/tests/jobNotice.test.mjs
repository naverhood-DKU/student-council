import test from 'node:test'
import assert from 'node:assert/strict'
import { jobNotice } from '../src/jobNotice.ts'

test('호출 제한 대기는 분석 진행 문구와 구분한다', () => {
  const notice = jobNotice({status:'RUNNING', quotaWaitUntil:'2026-10-11T10:00:00Z', lastError:null})
  assert.match(notice.title,/대기/)
  assert.match(notice.body,/정상 접수/)
  assert.match(notice.body,/화면을 닫아도/)
  assert.doesNotMatch(notice.body,/몇십 초/)
})
test('큐 대기와 실제 분석은 별개의 안내를 제공한다', () => {
  assert.match(jobNotice({status:'QUEUED',quotaWaitUntil:null,lastError:null}).title,/차례/)
  assert.match(jobNotice({status:'RUNNING',quotaWaitUntil:null,lastError:null}).title,/연결/)
})
