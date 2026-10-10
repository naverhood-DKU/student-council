package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 금지·제한 표현 낱말 검사. 의미를 이해하지 못하는 단순 검사라서 한계도 함께 확인한다. */
class RestrictionWordsTest {

    @Test
    void 금지_제한_표현이_있는_조항을_알아본다() {
        assertThat(RestrictionWords.mentionsRestriction("학생회비 사용을 금지한다.")).isTrue();
        assertThat(RestrictionWords.mentionsRestriction("학생회 회식비는 제외한다.")).isTrue();
        assertThat(RestrictionWords.mentionsRestriction("1인당 1만원을 넘지 못한다.")).isTrue();
        assertThat(RestrictionWords.mentionsRestriction("카드 사용이 불가하다.")).isTrue();
        assertThat(RestrictionWords.mentionsRestriction("영수증을 첨부하여 정산한다.")).isFalse();
        assertThat(RestrictionWords.mentionsRestriction("회장의 승인을 받아야 한다.")).isFalse();
    }

    @Test
    void 줄바꿈이나_공백이_끼어도_알아본다() {
        // 공백을 모두 없앤 글로 찾으므로 PDF 에서 줄이 갈라져도 알아본다.
        assertThat(RestrictionWords.mentionsRestriction("학생회비 사용을\n금 지한다")).isTrue();
        assertThat(RestrictionWords.mentionsRestriction("학생회비 사용을\n금지 한다")).isTrue();
    }

    @Test
    void 승인_표현이_함께_있으면_단순_금지가_아니다() {
        assertThat(RestrictionWords.isPlainRestriction("1인당 1만원을 넘지 못한다")).isTrue();
        assertThat(RestrictionWords.isPlainRestriction("현금사용을 원칙적으로 금지한다")).isTrue();
        assertThat(RestrictionWords.isPlainRestriction("현금사용 금지, 단 감사위원회의 승인을 받은 후 사용")).isFalse();
        assertThat(RestrictionWords.isPlainRestriction("영수증을 첨부하여 정산한다")).isFalse();
    }

    @Test
    void 한도와_금지를_가른다() {
        assertThat(RestrictionWords.kindOf("1인당 1만원을 넘지 못한다")).isEqualTo(GuidanceResult.LIMITED);
        assertThat(RestrictionWords.kindOf("학생회 회식에는 사용을 금지한다")).isEqualTo(GuidanceResult.PROHIBITED);
        assertThat(RestrictionWords.kindOf("최대 50% 까지 사용 가능하나 그 밖의 사용은 금지한다")).isEqualTo(GuidanceResult.PROHIBITED);
    }

    @Test
    void 지출_항목과_두_글자_조각이_겹쳐야_관련_있다고_본다() {
        assertThat(RestrictionWords.relatedTo("학생회 회식", "학생회 회식비 200000")).isTrue();
        assertThat(RestrictionWords.relatedTo("학생회 LT", "학생회 LT 숙박비")).isTrue();
        assertThat(RestrictionWords.relatedTo("1박 이상의 사전답사", "1박 사전답사 숙박비")).isTrue();
        // 흔한 조각(학생, 생회, 회비, 사용 …)만 겹치면 관련 없다
        assertThat(RestrictionWords.relatedTo("학생회 LT", "학생회비 집행 영수증")).isFalse();
        assertThat(RestrictionWords.relatedTo("모든 행사에 대한 학생회비 선지급", "동아리 지원금 신청")).isFalse();
    }

    @Test
    void 지출_항목을_모르면_모두_관련_있다고_본다() {
        assertThat(RestrictionWords.relatedTo("학생회 LT", null)).isTrue();
        assertThat(RestrictionWords.relatedTo("학생회 LT", "  ")).isTrue();
    }
}
