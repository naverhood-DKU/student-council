package kr.dku.councilflow.rule;

/**
 * 규정 문서의 성격. 지금은 "제n조"로 나뉘는 회칙·세칙·규정(REGULATION)만 올릴 수 있다.
 * 슬라이드 같은 안내 자료는 조항이 아니라서 별도 형식으로 다룰 예정이다(아직 없음).
 */
public enum RuleSourceType {
    REGULATION
}
