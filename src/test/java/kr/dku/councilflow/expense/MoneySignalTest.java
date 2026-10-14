package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneySignalTest {

    @Test
    void 돈과_관련된_낱말이나_금액_표기가_있으면_돈_관련이다() {
        assertThat(MoneySignal.mentionsMoney("LT 숙박비 결제")).isTrue();
        assertThat(MoneySignal.mentionsMoney("행사 물품 구매")).isTrue();
        assertThat(MoneySignal.mentionsMoney("간식 30만원 이내로 준비")).isTrue();
        assertThat(MoneySignal.mentionsMoney("참가비 5,000원 걷기")).isTrue();
        assertThat(MoneySignal.mentionsMoney("카카오톡 기프티콘 발송")).isTrue();
    }

    @Test
    void 돈과_관련_없는_글은_돈_관련이_아니다() {
        assertThat(MoneySignal.mentionsMoney("홍보 포스터 디자인")).isFalse();
        assertThat(MoneySignal.mentionsMoney("회의록 정리해서 공유")).isFalse();
        assertThat(MoneySignal.mentionsMoney("인스타그램 카드뉴스 올리기", null, "")).isFalse();
        assertThat(MoneySignal.mentionsMoney((String) null)).isFalse();
    }

    @Test
    void 여러_글_중_하나라도_돈_관련이면_돈_관련이다() {
        assertThat(MoneySignal.mentionsMoney("업무 정리", "재정부에서 정산")).isTrue();
    }

    @Test
    void 재정_총무_사무_회계_부서는_돈을_다루는_부서다() {
        assertThat(MoneySignal.isFinanceDepartment("재정부", null)).isTrue();
        assertThat(MoneySignal.isFinanceDepartment("총무부", "")).isTrue();
        assertThat(MoneySignal.isFinanceDepartment("사무국", null)).isTrue();
        assertThat(MoneySignal.isFinanceDepartment("기획부", "예산,회계,정산")).isTrue();
        assertThat(MoneySignal.isFinanceDepartment("홍보부", "SNS,포스터")).isFalse();
        assertThat(MoneySignal.isFinanceDepartment(null, null)).isFalse();
    }
}
