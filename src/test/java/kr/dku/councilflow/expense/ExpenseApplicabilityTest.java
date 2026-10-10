package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.dku.councilflow.expense.ExpenseApplicability.Answer;
import kr.dku.councilflow.expense.ExpenseApplicability.Verdict;
import kr.dku.councilflow.expense.ExpenseGuidanceValidator.Item;
import kr.dku.councilflow.expense.ExpenseGuidanceValidator.Validated;
import org.junit.jupiter.api.Test;

/** AI 재확인 결과를 안내에 반영하는 규칙(순수 계산). 항목을 지우지 않고 따로 두는지, 금지 배지가 어떻게 바뀌는지 본다. */
class ExpenseApplicabilityTest {

    private static final Verdict YES = new Verdict(Answer.YES, Answer.YES);
    private static final Verdict NOT_APPLY = new Verdict(Answer.NO, Answer.YES);
    private static final Verdict BAD_QUOTE = new Verdict(Answer.YES, Answer.NO);
    private static final Verdict UNSURE = new Verdict(Answer.UNSURE, Answer.UNSURE);

    private static Validated validated(List<Item> approvals, List<Item> evidence,
                                       List<GuidanceResult.Restriction> restrictions, String guidance) {
        ExpenseReviewStatus status = ExpenseGuidanceValidator.statusOf(approvals, evidence, restrictions, List.of());
        return new Validated(status, approvals, evidence, restrictions, List.of(), List.of(), guidance, 0, List.of());
    }

    private static Item item(String text, long clauseId) {
        return new Item(text, List.of(clauseId), "근거 구절 " + text);
    }

    private static GuidanceResult.Restriction restriction(String kind, String text, boolean related) {
        return new GuidanceResult.Restriction(kind, text, List.of(7L), "사용을 금지한다", related);
    }

    @Test
    void 해당하지_않는_승인은_체크리스트에서_빠지고_따로_남는다() {
        Validated v = validated(List.of(item("선거 홍보물 승인", 1), item("지출 결의서 결재", 2)), List.of(), List.of(),
                "요약");

        Validated result = ExpenseApplicability.apply(v, Map.of("A1", NOT_APPLY, "A2", YES));

        assertThat(result.approvals()).extracting(Item::text).containsExactly("지출 결의서 결재");
        assertThat(result.setAside()).hasSize(1);
        assertThat(result.setAside().get(0).kind()).isEqualTo("APPROVAL");
        assertThat(result.setAside().get(0).reason()).isEqualTo(GuidanceResult.NOT_APPLICABLE);
        assertThat(result.setAside().get(0).text()).isEqualTo("선거 홍보물 승인");
        assertThat(result.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);
    }

    @Test
    void 인용과_맞지_않는_증빙도_따로_둔다() {
        Validated v = validated(List.of(), List.of(item("견적서 3부 제출", 3)), List.of(), null);

        Validated result = ExpenseApplicability.apply(v, Map.of("E1", BAD_QUOTE));

        assertThat(result.evidence()).isEmpty();
        assertThat(result.setAside().get(0).kind()).isEqualTo("EVIDENCE");
        assertThat(result.setAside().get(0).reason()).isEqualTo(GuidanceResult.QUOTE_MISMATCH);
        assertThat(result.status()).isEqualTo(ExpenseReviewStatus.NEEDS_CHECK);
    }

    @Test
    void 모르겠다거나_답이_없으면_그대로_둔다() {
        Validated v = validated(List.of(item("가", 1), item("나", 2)), List.of(), List.of(), "요약");

        Validated result = ExpenseApplicability.apply(v, Map.of("A1", UNSURE));

        assertThat(result.approvals()).hasSize(2);
        assertThat(result.setAside()).isEmpty();
        assertThat(result.guidance()).isEqualTo("요약");
    }

    @Test
    void 글자_비교가_관련_없다고_한_금지를_AI가_해당한다고_보면_관련_있음으로_바뀐다() {
        // 기프티콘 ↔ 바코드 캡쳐: 글자는 겹치지 않지만 같은 일이다.
        Validated v = validated(List.of(), List.of(),
                List.of(restriction(GuidanceResult.PROHIBITED, "바코드를 캡쳐해 보내는 것", false)), "요약");
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);

        Validated result = ExpenseApplicability.apply(v, Map.of("R1", YES));

        assertThat(result.restrictions().get(0).relatedOrTrue()).isTrue();
        assertThat(result.status()).isEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
        assertThat(result.guidance()).isNull();
    }

    @Test
    void 글자가_겹쳐도_AI가_해당하지_않는다고_하면_배지를_거둔다() {
        Validated v = validated(List.of(), List.of(),
                List.of(restriction(GuidanceResult.PROHIBITED, "학생회 회식 금지", true)), null);
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);

        Validated result = ExpenseApplicability.apply(v, Map.of("R1", NOT_APPLY));

        assertThat(result.restrictions()).hasSize(1);
        assertThat(result.restrictions().get(0).relatedOrTrue()).isFalse();
        assertThat(result.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);
    }

    @Test
    void 금지의_인용_문구_맞음_여부는_보지_않는다() {
        // 금지는 목록 조항의 호를 text 로, 머리 문장을 quote 로 써서 항목 문구와 인용이 다를 수 있다.
        Validated v = validated(List.of(), List.of(),
                List.of(restriction(GuidanceResult.PROHIBITED, "학생회 LT", true)), null);

        Validated result = ExpenseApplicability.apply(v, Map.of("R1", BAD_QUOTE));

        assertThat(result.restrictions().get(0).relatedOrTrue()).isTrue();
        assertThat(result.status()).isEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
    }

    @Test
    void 이름은_종류별로_나온_순서대로_붙는다() {
        Validated v = validated(List.of(item("승인1", 1), item("승인2", 2)), List.of(item("증빙1", 3)),
                List.of(restriction(GuidanceResult.LIMITED, "한도", true)), null);

        List<ExpenseApplicability.Target> targets = ExpenseApplicability.targets(v);

        assertThat(targets).extracting(ExpenseApplicability.Target::name).containsExactly("A1", "A2", "E1", "R1");
        assertThat(targets.get(3).kind()).isEqualTo("한도·조건");
    }
}
