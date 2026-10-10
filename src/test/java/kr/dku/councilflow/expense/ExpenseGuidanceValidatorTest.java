package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.dku.councilflow.expense.ExpenseGuidanceValidator.Candidate;
import kr.dku.councilflow.expense.ExpenseGuidanceValidator.Validated;
import org.junit.jupiter.api.Test;

/**
 * 모델 안내의 의미 검증. 후보 밖 조항·지어낸 인용·판정 표현은 걸러지고, 걸러낸 개수가 남는다.
 * 순수 계산이라 스프링과 DB 없이 돈다.
 */
class ExpenseGuidanceValidatorTest {

    private static final String C1_TEXT = "제1조(특별지출) 100만원 이상의 지출은\n사전에 회장의 승인을 받아야 한다.";
    private static final String C2_TEXT = "제2조(증빙) 모든 지출은 영수증을 첨부하여 재정부장의 확인을 받는다.";
    private static final String C3_TEXT = "제3조(특별지출) 50만원 이상의 지출은 부회장의 사전 승인을 받는다.";

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("C1", 101, C1_TEXT),
            new Candidate("C2", 102, C2_TEXT),
            new Candidate("C3", 103, C3_TEXT));

    private static ParsedGuidance.Cited cited(String text, String quote, String... names) {
        return new ParsedGuidance.Cited(text, List.of(names), quote);
    }

    private static ParsedGuidance guidance(List<ParsedGuidance.Cited> approvals, List<ParsedGuidance.Cited> evidence,
                                           List<ParsedGuidance.Conflict> conflicts, String note) {
        return new ParsedGuidance(approvals, evidence, List.of(), conflicts, List.of(), note);
    }

    @Test
    void 정상_인용은_통과하고_조항_이름은_DB_id로_바뀐다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장의 사전 승인", "사전에 회장의 승인을 받아야 한다", "C1")),
                List.of(cited("영수증 첨부", "영수증을 첨부하여", "C2")),
                List.of(), "후보 조항을 확인하세요."), CANDIDATES);

        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);
        assertThat(v.approvals()).hasSize(1);
        assertThat(v.approvals().get(0).clauseIds()).containsExactly(101L);
        assertThat(v.evidence().get(0).clauseIds()).containsExactly(102L);
        assertThat(v.droppedCount()).isZero();
        assertThat(v.guidance()).isEqualTo("후보 조항을 확인하세요.");
    }

    @Test
    void 줄바꿈이나_공백만_다른_인용은_통과한다() {
        // 원문은 "지출은\n사전에" 이지만 모델은 한 줄로 옮겼다. 공백 차이만 무시한다.
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장 승인", "지출은 사전에 회장의 승인", "C1")), List.of(), List.of(), ""), CANDIDATES);

        assertThat(v.approvals()).hasSize(1);
        assertThat(v.droppedCount()).isZero();
    }

    @Test
    void 정규화_형태가_달라도_같은_글자면_통과한다() {
        // 조합형(NFD) 한글로 옮긴 인용도 NFC 로 맞춰 비교한다.
        String nfd = java.text.Normalizer.normalize("영수증을 첨부하여", java.text.Normalizer.Form.NFD);
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(), List.of(cited("영수증", nfd, "C2")), List.of(), ""), CANDIDATES);

        assertThat(v.evidence()).hasSize(1);
    }

    @Test
    void 후보_목록_밖_이름은_항목을_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "C9"),
                        cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "101"),
                        cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "C1", "C7")),
                List.of(), List.of(), ""), CANDIDATES);

        assertThat(v.approvals()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(3);
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.NEEDS_CHECK);
        assertThat(v.droppedNotes()).anyMatch(n -> n.contains("후보 조항 목록에 없는 조항") && n.contains("3개"));
    }

    @Test
    void 조항_이름이_없으면_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(new ParsedGuidance.Cited("회장 승인", List.of(), "사전에 회장의 승인")),
                List.of(), List.of(), ""), CANDIDATES);

        assertThat(v.approvals()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(1);
    }

    @Test
    void 다른_조항의_문장을_인용하면_버린다() {
        // 영수증 문장은 C2 에 있다. C1 을 근거로 들었으므로 C1 원문에서는 찾을 수 없다.
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(), List.of(cited("영수증 첨부", "영수증을 첨부하여", "C1")), List.of(), ""), CANDIDATES);

        assertThat(v.evidence()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(1);
        assertThat(v.droppedNotes()).anyMatch(n -> n.contains("원문에서 확인되지 않는 인용"));
    }

    @Test
    void 지어낸_인용은_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("총장 승인", "사전에 총장의 승인을 받아야 한다", "C1")), List.of(), List.of(), ""), CANDIDATES);

        assertThat(v.approvals()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(1);
    }

    @Test
    void 너무_짧은_인용은_버린다() {
        // "승인을" 은 원문에 있지만 3글자라 근거로 쓸 수 없다. 4글자("승인을 받")는 통과한다.
        Validated tooShort = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("승인", "승인을", "C1")), List.of(), List.of(), ""), CANDIDATES);
        Validated enough = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("승인", "승인을 받", "C1")), List.of(), List.of(), ""), CANDIDATES);

        assertThat(tooShort.approvals()).isEmpty();
        assertThat(tooShort.droppedCount()).isEqualTo(1);
        assertThat(enough.approvals()).hasSize(1);
    }

    @Test
    void 통과와_탈락이_섞이면_통과분만_남고_탈락_개수가_남는다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "C1"),
                        cited("총장 승인", "사전에 총장의 승인을 받아야 한다", "C1")),
                List.of(cited("영수증", "영수증을 첨부하여", "C2"),
                        cited("견적서", "견적서 두 장을 첨부", "C8")),
                List.of(), "참고 안내"), CANDIDATES);

        assertThat(v.approvals()).hasSize(1);
        assertThat(v.evidence()).hasSize(1);
        assertThat(v.droppedCount()).isEqualTo(2);
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);
    }

    @Test
    void 충돌은_서로_다른_후보_조항_둘_이상을_가리켜야_한다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(List.of(), List.of(), List.of(
                new ParsedGuidance.Conflict("승인 기준 금액이 100만원과 50만원으로 다르다", List.of("C1", "C3")),
                new ParsedGuidance.Conflict("조항 하나만 가리킴", List.of("C1")),
                new ParsedGuidance.Conflict("같은 조항만 두 번", List.of("C1", "[c1]")),
                new ParsedGuidance.Conflict("목록 밖 조항", List.of("C1", "C9"))), ""), CANDIDATES);

        assertThat(v.conflicts()).hasSize(1);
        assertThat(v.conflicts().get(0).clauseIds()).containsExactly(101L, 103L);
        assertThat(v.droppedCount()).isEqualTo(3);
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.POSSIBLE_CONFLICT);
    }

    @Test
    void 충돌이_남으면_승인_항목이_있어도_충돌_가능으로_본다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "C1")), List.of(),
                List.of(new ParsedGuidance.Conflict("기준 금액이 다르다", List.of("C1", "C3"))), ""), CANDIDATES);

        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.POSSIBLE_CONFLICT);
        assertThat(v.approvals()).hasSize(1);
    }

    @Test
    void 판정_표현이_든_안내문은_버린다() {
        for (String verdict : List.of("이 지출은 문제없음입니다.", "별도 승인 불필요합니다.", "규정을 통과합니다.", "이상 없음")) {
            Validated v = ExpenseGuidanceValidator.validate(guidance(
                    List.of(cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "C1")), List.of(), List.of(), verdict), CANDIDATES);

            assertThat(v.guidance()).as(verdict).isNull();
            assertThat(v.droppedCount()).as(verdict).isEqualTo(1);
            assertThat(v.droppedNotes()).as(verdict).anyMatch(n -> n.contains("판정 표현"));
            // 안내문만 버리고 근거가 확인된 항목은 남는다.
            assertThat(v.approvals()).as(verdict).hasSize(1);
        }
    }

    @Test
    void 판정_표현이_든_승인_증빙_항목도_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("승인 불필요", "사전에 회장의 승인을 받아야 한다", "C1")),
                List.of(cited("영수증 문제없음", "영수증을 첨부하여", "C2")), List.of(), ""), CANDIDATES);

        assertThat(v.approvals()).isEmpty();
        assertThat(v.evidence()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(2);
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.NEEDS_CHECK);
    }

    @Test
    void 아무것도_남지_않으면_NEEDS_CHECK_이고_근거_없는_안내문도_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(new ParsedGuidance(List.of(), List.of(), List.of(), List.of(),
                List.of("금액을 알려 주세요"), "조항을 확인하세요."), CANDIDATES);

        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.NEEDS_CHECK);
        assertThat(v.guidance()).isNull();
        assertThat(v.droppedNotes()).anyMatch(n -> n.contains("근거가 없는 참고 안내문"));
        // 더 필요한 사실은 규정에 대한 주장이 아니라서 남긴다.
        assertThat(v.missingFacts()).containsExactly("금액을 알려 주세요");
    }

    @Test
    void 같은_항목은_한_번만_남긴다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장의 사전 승인", "사전에 회장의 승인을 받아야 한다", "C1"),
                        cited("회장의  사전 승인", "회장의 승인을 받아야", "C1")),
                List.of(), List.of(), ""), CANDIDATES);

        assertThat(v.approvals()).hasSize(1);
    }

    @Test
    void 조항_이름은_대괄호와_대소문자를_가려_받는다() {
        Validated v = ExpenseGuidanceValidator.validate(guidance(
                List.of(cited("회장 승인", "사전에 회장의 승인을 받아야 한다", "[C1]"),
                        cited("영수증", "영수증을 첨부하여", " c2 ")), List.of(), List.of(), ""), CANDIDATES);

        // 둘 다 승인 항목으로 넣었다. 이름 표기가 달라도 둘 다 후보 조항 C1, C2 로 읽힌다.
        assertThat(v.approvals()).hasSize(2);
        assertThat(v.approvals().get(0).clauseIds()).containsExactly(101L);
        assertThat(v.approvals().get(1).clauseIds()).containsExactly(102L);
        assertThat(v.droppedCount()).isZero();
    }

    // ---- 금지·제한 ----

    private static final String BAN_TEXT = "제15조의3(학생회비 사용 제한 항목) 다음 각 호에 대한 학생회비 사용을 금지한다. 1. 학생회 회식 2. 학생회 LT";
    private static final String LIMIT_TEXT = "제5조(식비) 회의 간식비와 식비는 1인당 1만원을 넘지 못한다.";
    private static final String CASH_TEXT = "제15조의1(경비의 사용) 현금사용을 원칙적으로 금지한다. 단, 부득이하게 현금사용 시 감사위원회의 승인을 받은 후 사용하여야 한다.";

    private static final List<Candidate> RESTRICTION_CANDIDATES = List.of(
            new Candidate("C1", 201, BAN_TEXT),
            new Candidate("C2", 202, LIMIT_TEXT),
            new Candidate("C3", 203, CASH_TEXT));

    private static ParsedGuidance.Restriction restriction(String kind, String text, String quote, String... names) {
        return new ParsedGuidance.Restriction(kind, text, List.of(names), quote);
    }

    private static ParsedGuidance withRestrictions(List<ParsedGuidance.Restriction> restrictions,
                                                   List<ParsedGuidance.Cited> approvals) {
        return new ParsedGuidance(approvals, List.of(), restrictions, List.of(), List.of(), "");
    }

    @Test
    void 금지_조항이_확인되면_절차_확인이_아니라_금지_가능성이다() {
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(
                List.of(restriction("PROHIBITED", "학생회 LT 에는 학생회비를 쓸 수 없다",
                        "다음 각 호에 대한 학생회비 사용을 금지한다", "C1")), List.of()), RESTRICTION_CANDIDATES);

        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
        assertThat(v.restrictions()).hasSize(1);
        assertThat(v.restrictions().get(0).clauseIds()).containsExactly(201L);
        assertThat(v.restrictions().get(0).kind()).isEqualTo("PROHIBITED");
        assertThat(v.droppedCount()).isZero();
    }

    @Test
    void 한도만_있으면_금지_가능성이_아니라_절차_확인이다() {
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(
                List.of(restriction("LIMITED", "간식비는 1인당 1만원이 한도다", "1인당 1만원을 넘지 못한다", "C2")),
                List.of()), RESTRICTION_CANDIDATES);

        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);
        assertThat(v.restrictions()).hasSize(1);
    }

    @Test
    void 금지가_있으면_충돌과_승인보다_우선한다() {
        ParsedGuidance parsed = new ParsedGuidance(
                List.of(cited("현금 사용 승인", "감사위원회의 승인을 받은 후 사용", "C3")), List.of(),
                List.of(restriction("PROHIBITED", "회식은 금지", "학생회비 사용을 금지한다", "C1")),
                List.of(new ParsedGuidance.Conflict("기준이 다르다", List.of("C1", "C2"))), List.of(), "");

        Validated v = ExpenseGuidanceValidator.validate(parsed, RESTRICTION_CANDIDATES);

        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
        assertThat(v.approvals()).hasSize(1);
        assertThat(v.conflicts()).hasSize(1);
    }

    @Test
    void 금지_항목도_후보_밖_이름과_지어낸_인용은_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(List.of(
                restriction("PROHIBITED", "후보 밖", "다음 각 호에 대한 학생회비 사용을 금지한다", "C9"),
                restriction("PROHIBITED", "지어낸 인용", "원문 어디에도 없는 금지 문장입니다", "C1")), List.of()),
                RESTRICTION_CANDIDATES);

        assertThat(v.restrictions()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(2);
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.NEEDS_CHECK);
    }

    @Test
    void 승인으로_분류됐지만_인용이_금지만_말하면_서버가_금지로_옮긴다() {
        // 모델이 한도 조항을 "필요한 승인"으로 분류한 경우. 문구는 버리고 서버가 확인한 인용 구절을 쓴다.
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(List.of(),
                List.of(cited("회의 간식 구입에 대한 승인", "1인당 1만원을 넘지 못한다", "C2"))), RESTRICTION_CANDIDATES);

        assertThat(v.approvals()).isEmpty();
        assertThat(v.restrictions()).hasSize(1);
        assertThat(v.restrictions().get(0).text()).isEqualTo("1인당 1만원을 넘지 못한다");
        assertThat(v.restrictions().get(0).kind()).isEqualTo("LIMITED");
        assertThat(v.droppedCount()).isZero();
        assertThat(v.droppedNotes()).anyMatch(n -> n.contains("금지·제한으로 옮겼습니다"));
    }

    @Test
    void 예외로_승인을_받으면_허용하는_조항은_승인으로_둔다() {
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(List.of(),
                List.of(cited("현금 사용은 감사위원회 승인 후", "현금사용 시 감사위원회의 승인을 받은 후 사용하여야 한다", "C3"))),
                RESTRICTION_CANDIDATES);

        assertThat(v.approvals()).hasSize(1);
        assertThat(v.restrictions()).isEmpty();
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.PROCEDURE_FOUND);
    }

    @Test
    void 지출_항목과_관련_없어_보이는_금지는_지우지_않고_배지도_만들지_않는다() {
        ParsedGuidance parsed = withRestrictions(List.of(
                restriction("PROHIBITED", "학생회 LT", "다음 각 호에 대한 학생회비 사용을 금지한다", "C1")), List.of());

        // 지출 항목이 영수증 증빙이면 "학생회 LT" 금지는 직접 관련이 없다("학생회" 같은 흔한 조각은 세지 않는다).
        Validated v = ExpenseGuidanceValidator.validate(parsed, RESTRICTION_CANDIDATES, "학생회비 집행 영수증 증빙");

        assertThat(v.restrictions()).hasSize(1);
        assertThat(v.restrictions().get(0).related()).isFalse();
        assertThat(v.status()).isNotEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
        assertThat(v.droppedNotes()).anyMatch(n -> n.contains("직접 관련이 없어 보이는 금지·제한 1개"));
    }

    @Test
    void 지출_항목과_관련_있는_금지는_금지_가능성으로_본다() {
        ParsedGuidance parsed = withRestrictions(List.of(
                restriction("PROHIBITED", "학생회 LT", "다음 각 호에 대한 학생회비 사용을 금지한다", "C1")), List.of());

        Validated v = ExpenseGuidanceValidator.validate(parsed, RESTRICTION_CANDIDATES, "학생회 LT 숙박비 학생회비");

        assertThat(v.restrictions().get(0).related()).isTrue();
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
    }

    @Test
    void 금지_조각이_지출_항목에도_있어도_흔한_조각만_같으면_관련_없다고_본다() {
        ParsedGuidance parsed = withRestrictions(List.of(
                restriction("PROHIBITED", "학생회 회식", "다음 각 호에 대한 학생회비 사용을 금지한다", "C1")), List.of());

        // "학생회", "학생회비", "사용" 같은 조각만 겹친다.
        Validated v = ExpenseGuidanceValidator.validate(parsed, RESTRICTION_CANDIDATES, "학생회비 사용 동아리 지원금");

        assertThat(v.restrictions().get(0).related()).isFalse();
        assertThat(v.status()).isNotEqualTo(ExpenseReviewStatus.POSSIBLY_RESTRICTED);
    }

    @Test
    void 금지_조항의_호만_인용해_승인으로_바꾼_항목은_버린다() {
        // "2. 학생회 LT" 에는 승인을 말하는 낱말이 없다. 금지 조항(C1)을 근거로 든 승인 항목이라 확정된 사실처럼 보이면 안 된다.
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(List.of(),
                List.of(cited("학생회 LT 숙박비 결재", "다음 각 호 2. 학생회 LT", "C1"))), RESTRICTION_CANDIDATES,
                "학생회 LT 숙박비");

        assertThat(v.approvals()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(1);
        assertThat(v.droppedNotes()).anyMatch(n -> n.contains("승인·증빙 내용이 아닌 구절"));
        assertThat(v.status()).isEqualTo(ExpenseReviewStatus.NEEDS_CHECK);
    }

    @Test
    void 금지_표현이_있는_조항이라도_인용에_승인이_있으면_남긴다() {
        Validated v = ExpenseGuidanceValidator.validate(withRestrictions(List.of(),
                List.of(cited("현금 사용은 감사위원회 승인 후", "감사위원회의 승인을 받은 후 사용하여야 한다", "C3"))),
                RESTRICTION_CANDIDATES, "행사 현금 지출");

        assertThat(v.approvals()).hasSize(1);
    }

    @Test
    void 증빙_항목도_금지_조항에서_서류를_말하지_않는_구절이면_버린다() {
        Validated v = ExpenseGuidanceValidator.validate(new ParsedGuidance(List.of(),
                List.of(cited("LT 증빙", "다음 각 호 2. 학생회 LT", "C1")),
                List.of(), List.of(), List.of(), ""), RESTRICTION_CANDIDATES, "학생회 LT 숙박비");

        assertThat(v.evidence()).isEmpty();
        assertThat(v.droppedCount()).isEqualTo(1);
    }
}
