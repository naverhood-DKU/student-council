package kr.dku.councilflow.expense;

import java.util.List;

/**
 * 모델이 준 지출 안내를 JSON 모양만 확인해 옮긴 것(1단계 검증 통과, 아직 의미 검증 전).
 * 조항은 모델에게 보낸 이름([C1]…)으로 되어 있고, DB id 가 아니다.
 */
public record ParsedGuidance(List<Cited> approvals, List<Cited> evidence, List<Conflict> conflicts,
                             List<String> missingFacts, String guidance) {

    /** 필요한 승인 또는 증빙 한 줄과 그 근거(조항 이름, 원문에서 옮긴 구절). */
    public record Cited(String text, List<String> clauseNames, String quote) {
    }

    public record Conflict(String description, List<String> clauseNames) {
    }
}
