import type { Job } from './api'

/** 시간 약속 없이 접수·차례 대기·실제 호출 제한 대기를 구분한다. */
export function jobNotice(job: Pick<Job, 'type' | 'status' | 'lastError' | 'quotaWaitUntil'>) {
  if (job.type === 'TRANSCRIBE') return {
    title: job.status === 'QUEUED' ? '녹음이 접수됐습니다. 글로 옮길 차례를 기다리고 있습니다.' : '녹음을 글로 옮기고 있습니다.',
    body: '끝나면 회의 대본이 나오고, 그다음 분석을 시작할 수 있습니다. 이 화면을 닫아도 작업은 유지됩니다.',
  }
  if (job.quotaWaitUntil) return {
    title: '현재 분석 요청이 많아 잠시 대기 중입니다.',
    body: '요청은 정상 접수됐으며, 처리 가능해지면 자동으로 분석을 시작합니다. 이 화면을 닫아도 작업은 유지됩니다.',
  }
  if (job.status === 'QUEUED') return {
    title: '회의록이 접수됐습니다. 분석 차례를 기다리고 있습니다.',
    body: '앞선 분석이 끝나면 자동으로 시작됩니다. 이 화면을 닫아도 작업은 유지됩니다.',
  }
  return {
    title: 'AI가 결정사항과 부서별 업무를 연결하고 있습니다.',
    body: '회의록 길이와 이용량에 따라 시간이 걸릴 수 있습니다. 이 화면을 닫아도 분석은 계속됩니다.',
  }
}