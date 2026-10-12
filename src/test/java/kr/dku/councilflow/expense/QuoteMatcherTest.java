package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 인용 구절 확인: 조각별로 원문에 순서대로 그대로 있어야 하고, 지어낸 조각이 하나라도 있으면 탈락한다. */
class QuoteMatcherTest {

    private static final String BAN = "제15조의3(학생회비 사용 제한 항목) ① 다음 각 호에 대한 학생회비 사용을 금지한다. "
            + "1. 학생회 회식: 모임을 말한다. 2. 학생회 LT 3. 학생회비 투입 행사 중 분실로 인한 지출 "
            + "4. 모든 행사에 대한 학생회비 선지급 6. 1박 이상의 사전답사";

    @Test
    void 연속된_한_구절은_통과한다() {
        assertThat(QuoteMatcher.isVerbatim(BAN, "다음 각 호에 대한 학생회비 사용을 금지한다")).isTrue();
    }

    @Test
    void 머리_문장과_호를_이어_붙인_인용은_조각이_순서대로_있으면_통과한다() {
        // 중간 호(1. 학생회 회식)를 건너뛴 인용
        assertThat(QuoteMatcher.isVerbatim(BAN, "다음 각 호에 대한 학생회비 사용을 금지한다. 2. 학생회 LT")).isTrue();
        assertThat(QuoteMatcher.isVerbatim(BAN, "① 다음 각 호에 대한 학생회비 사용을 금지한다. 6. 1박 이상의 사전답사")).isTrue();
    }

    @Test
    void 조각의_순서가_뒤집히면_탈락한다() {
        assertThat(QuoteMatcher.isVerbatim(BAN, "6. 1박 이상의 사전답사 다음 각 호에 대한 학생회비 사용을 금지한다.")).isFalse();
    }

    @Test
    void 지어낸_조각이_하나라도_섞이면_탈락한다() {
        assertThat(QuoteMatcher.isVerbatim(BAN, "다음 각 호에 대한 학생회비 사용을 금지한다. 2. 학생회 MT")).isFalse();
        assertThat(QuoteMatcher.isVerbatim(BAN, "학생회비 사용을 허용한다. 2. 학생회 LT")).isFalse();
    }

    @Test
    void 공백_따옴표_문장부호_동그라미_숫자_차이는_무시한다() {
        String clause = "③ 현금사용을 원칙적으로 금지한다. 단, 부득이하게 ‘카카오톡 선물하기’만 사용 <신설 2020. 9. 8.>";
        assertThat(QuoteMatcher.isVerbatim(clause, "현금사용을  원칙적으로 금지한다. 단 부득이하게 \"카카오톡 선물하기\" 만 사용")).isTrue();
        assertThat(QuoteMatcher.isVerbatim(clause, "③현금사용을 원칙적으로 금지한다")).isTrue();
    }

    @Test
    void 너무_짧은_인용은_길이로_걸러진다() {
        assertThat(QuoteMatcher.length("2. 학생회 LT")).isEqualTo(5); // 번호 머리 "2." 는 빼고 학생회LT
        assertThat(QuoteMatcher.length("승인을")).isEqualTo(3);
        assertThat(QuoteMatcher.length("1. 2.")).isZero();
    }

    @Test
    void 말줄임표로_이은_인용도_조각별로_확인한다() {
        assertThat(QuoteMatcher.isVerbatim(BAN, "다음 각 호에 대한 학생회비 사용을 금지한다 … 학생회 LT")).isTrue();
        assertThat(QuoteMatcher.isVerbatim(BAN, "다음 각 호에 대한 학생회비 사용을 금지한다 ... 학생회 엠티")).isFalse();
    }

    @Test
    void 빈_인용은_탈락한다() {
        assertThat(QuoteMatcher.isVerbatim(BAN, "")).isFalse();
        assertThat(QuoteMatcher.isVerbatim(BAN, "   ")).isFalse();
    }
}
