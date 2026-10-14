package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 업무 카드 배지: 서버 검증을 통과한 안내에서만 정하고, "문제없음" 표시는 없다. */
class RuleBadgeTest {

    private static GuidanceResult result(String outcome, List<GuidanceResult.Restriction> restrictions,
                                         List<GuidanceResult.Conflict> conflicts) {
        return new GuidanceResult(outcome, conflicts, restrictions, 0, List.of(), "2026-10-14T00:00:00Z");
    }

    private static GuidanceResult.Restriction restriction(String kind, boolean related) {
        return new GuidanceResult.Restriction(kind, "내용", List.of(1L), "인용", related);
    }

    @Test
    void 이_지출과_관련_있는_금지가_남으면_금지_가능성이다() {
        GuidanceResult ai = result("POSSIBLY_RESTRICTED", List.of(restriction("PROHIBITED", true)), List.of());
        assertThat(RuleBadge.of(ai, false, false)).isEqualTo(RuleBadge.PROHIBITED);
    }

    @Test
    void 금지가_관련_없어_보이면_금지_가능성을_달지_않는다() {
        GuidanceResult ai = result("POSSIBLY_RESTRICTED", List.of(restriction("PROHIBITED", false)), List.of());
        assertThat(RuleBadge.of(ai, false, false)).isEqualTo(RuleBadge.NONE);
    }

    @Test
    void 한도_제한이나_조항_충돌은_위험이다() {
        assertThat(RuleBadge.of(result("PROCEDURE_FOUND", List.of(restriction("LIMITED", true)), List.of()), false, false))
                .isEqualTo(RuleBadge.RISK);
        GuidanceResult conflict = result("POSSIBLE_CONFLICT", List.of(),
                List.of(new GuidanceResult.Conflict("서로 다르게 말함", List.of(1L, 2L))));
        assertThat(RuleBadge.of(conflict, false, false)).isEqualTo(RuleBadge.RISK);
    }

    @Test
    void 금지와_위험이_함께_있으면_금지가_먼저다() {
        GuidanceResult ai = result("POSSIBLY_RESTRICTED",
                List.of(restriction("LIMITED", true), restriction("PROHIBITED", true)), List.of());
        assertThat(RuleBadge.of(ai, false, false)).isEqualTo(RuleBadge.PROHIBITED);
    }

    @Test
    void 승인_증빙만_있으면_배지_없이_관련_조항_보기다() {
        assertThat(RuleBadge.of(result("PROCEDURE_FOUND", List.of(), List.of()), false, false)).isEqualTo(RuleBadge.NONE);
    }

    @Test
    void 후보가_없거나_확인된_것이_없으면_규정_확인_안_됨이다() {
        assertThat(RuleBadge.of(null, true, false)).isEqualTo(RuleBadge.UNCHECKED);
        assertThat(RuleBadge.of(result("NEEDS_CHECK", List.of(), List.of()), false, false)).isEqualTo(RuleBadge.UNCHECKED);
    }

    @Test
    void 안내를_기다리는_중이거나_실패하면_그대로_알린다() {
        assertThat(RuleBadge.of(null, false, false)).isEqualTo(RuleBadge.PENDING);
        assertThat(RuleBadge.of(null, false, true)).isEqualTo(RuleBadge.FAILED);
    }

    @Test
    void 모든_배지_설명은_확정이나_안전을_말하지_않는다() {
        for (RuleBadge badge : RuleBadge.values()) {
            String summary = TaskRuleChecks.summary(badge);
            assertThat(summary).isNotBlank();
            assertThat(summary).doesNotContain("문제없음").doesNotContain("승인 불필요").doesNotContain("통과");
        }
    }
}
