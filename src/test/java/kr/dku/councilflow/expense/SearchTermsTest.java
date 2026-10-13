package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 후보 조항 검색어 만들기. 스프링 없이 글자만으로 확인한다. */
class SearchTermsTest {

    @Test
    void 낱말을_공백으로_이어_한_줄로_만든다() {
        assertThat(SearchTerms.build("회식비", "30만원", null)).isEqualTo("회식비 30만원");
    }

    @Test
    void 기호와_검색_연산자는_공백으로_바꾼다() {
        assertThat(SearchTerms.build("MT(엠티) 숙박비, 간식+음료")).isEqualTo("MT 엠티 숙박비 간식 음료");
        assertThat(SearchTerms.build("+회식 -영수증 \"abc\" * @>")).isEqualTo("회식 영수증 abc");
    }

    @Test
    void 한_글자_낱말은_버리고_같은_낱말은_한_번만_쓴다() {
        assertThat(SearchTerms.build("가 나 다 회식 회식")).isEqualTo("회식");
    }

    @Test
    void 쓸_낱말이_없으면_null() {
        assertThat(SearchTerms.build((String) null)).isNull();
        assertThat(SearchTerms.build("  ", "!!! ??", "가")).isNull();
    }

    @Test
    void 금액은_규정에_쓰이는_한국식_표기로_바꾼다() {
        assertThat(SearchTerms.amountTerms(300_000L)).isEqualTo("30만원");
        assertThat(SearchTerms.amountTerms(50_000L)).isEqualTo("5만원");
        assertThat(SearchTerms.amountTerms(1_500_000L)).isEqualTo("150만원");
        assertThat(SearchTerms.amountTerms(15_000L)).isEqualTo("1만5천원");
        assertThat(SearchTerms.amountTerms(5_000L)).isEqualTo("5천원");
        assertThat(SearchTerms.amountTerms(100_000_000L)).isEqualTo("1억원");
        assertThat(SearchTerms.amountTerms(120_000_000L)).isEqualTo("1억2000만원");
        // 천 원 단위로 떨어지지 않으면 만 원 단위로 쓴다
        assertThat(SearchTerms.amountTerms(312_345L)).isEqualTo("31만원");
    }

    @Test
    void 금액이_없거나_너무_작으면_금액_검색어를_만들지_않는다() {
        assertThat(SearchTerms.amountTerms(null)).isNull();
        assertThat(SearchTerms.amountTerms(0L)).isNull();
        assertThat(SearchTerms.amountTerms(-5L)).isNull();
        assertThat(SearchTerms.amountTerms(750L)).isNull();
    }

    @Test
    void 너무_길면_낱말_단위로_자른다() {
        String long1 = "가나다라마바사아자차".repeat(1); // 10자
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            many.append(long1).append(i).append(' ');
        }

        String query = SearchTerms.build(many.toString());

        assertThat(query).isNotNull();
        assertThat(query.length()).isLessThanOrEqualTo(SearchTerms.MAX_CHARS);
        assertThat(query).doesNotEndWith(" ");
    }
}
