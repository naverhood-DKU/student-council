package kr.dku.councilflow.meeting;

public enum MeetingStatus {
    /** 발언 단위로 나눠 저장했고 아직 분석 전 */
    READY,
    /** 분석 작업이 대기 중이거나 도는 중 */
    ANALYZING,
    /** AI 후보가 만들어져 사람의 검토를 기다림 */
    ANALYZED,
    /** 분석이 실패함(사유는 Job.last_error) */
    ANALYSIS_FAILED
}
