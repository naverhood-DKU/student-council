package kr.dku.councilflow.job;

public enum JobType {
    /** 녹음 전사 (단계 3) */
    TRANSCRIBE,
    /** 회의 분석: 회의록 → 결정·업무 후보 */
    ANALYZE,
    /** 지출 절차 안내 (단계 4) */
    EXPENSE
}
