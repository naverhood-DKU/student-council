package kr.dku.councilflow.expense;

/**
 * 업무 카드에 보이는 규정 확인 표시. 지출 검토의 상태와 서버 검증을 통과한 AI 안내에서 정한다.
 * "문제없음" 표시는 없다(원칙 5). 표시가 없다는 것이 안전하다는 뜻이 아니다.
 *
 * PROHIBITED  이 업무와 직접 관련 있어 보이는 금지 조항이 있다. "금지 가능성"이며 확정이 아니다.
 * RISK        유사한 행위·한도·조항끼리 충돌이 있어 보인다. "위험"이며 확정이 아니다.
 * NONE        서버가 확인한 금지·위험이 없다. 필요한 승인·증빙이 있으면 관련 조항을 볼 수 있다. 안전하다는 뜻은 아니다.
 * UNCHECKED   관련 조항을 찾지 못했거나 서버가 확인한 것이 없다. "규정 확인 안 됨"
 * PENDING     AI 안내를 기다리는 중이다.
 * FAILED      AI 안내가 실패했다. 후보 조항은 볼 수 있다.
 */
public enum RuleBadge {
    PROHIBITED("금지 가능성"),
    RISK("위험"),
    NONE("관련 조항 보기"),
    UNCHECKED("규정 확인 안 됨"),
    PENDING("규정 확인 중"),
    FAILED("규정 확인 못 함");

    private final String label;

    RuleBadge(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @param ai          서버 검증을 통과한 AI 안내(아직 없으면 null). 사람이 검토 완료한 뒤에도 이 값으로 정한다

     * @param noCandidate 후보 조항이 하나도 없다
     * @param jobFailed   AI 안내 작업이 최종 실패했다
     */
    static RuleBadge of(GuidanceResult ai, boolean noCandidate, boolean jobFailed) {
        if (noCandidate) {
            return UNCHECKED;
        }
        if (ai == null) {
            return jobFailed ? FAILED : PENDING;
        }
        boolean prohibited = ai.restrictionsOrEmpty().stream()
                .anyMatch(r -> GuidanceResult.PROHIBITED.equals(r.kind()) && r.relatedOrTrue());
        if (prohibited) {
            return PROHIBITED;
        }
        boolean limited = ai.restrictionsOrEmpty().stream()
                .anyMatch(r -> GuidanceResult.LIMITED.equals(r.kind()) && r.relatedOrTrue());
        boolean conflict = ai.conflicts() != null && !ai.conflicts().isEmpty();
        if (limited || conflict) {
            return RISK;
        }
        if (ExpenseReviewStatus.NEEDS_CHECK.name().equals(ai.outcome())) {
            return UNCHECKED;
        }
        return NONE;
    }
}
