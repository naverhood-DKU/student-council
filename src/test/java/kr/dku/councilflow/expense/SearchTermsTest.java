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
