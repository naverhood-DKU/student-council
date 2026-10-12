package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClauseParagraphsTest {

    private static final String CLAUSE = String.join("\n",
            "제15조의1(경비의 사용) <신설 2016. 9. 21.>",
            "① 피감사기구는 경비를 회계통장에 입금하여 사용하여야 한다.",
            "③ 현금사용을 원칙적으로 금지한다. 단, 부득이하게 현금사용 시 승인을 받은 후 사용하여야 한다.",
            "⑥ 카카오톡 기프티콘을 보낼 시, 각 개인에게 따로 결제하여 보내야하며 '카카오톡 선물하기'",
            "만을 사용한다. (학생회비 카드 하나로 모두 결제하여 바코드 캡쳐하여 보내는 것은 금지 한다.)",
            "⑦ 동아리 지원금은 신청서를 작성하여 제출해야 한다.");

    @Test
    void 동그라미_숫자로_문단을_나누고_이어진_줄은_한_문단으로_둔다() {
        List<String> paragraphs = ClauseParagraphs.split(CLAUSE);

        assertThat(paragraphs).hasSize(5);
        assertThat(paragraphs.get(0)).startsWith("제15조의1");
        assertThat(paragraphs.get(3)).startsWith("⑥").contains("바코드 캡쳐");
    }

    @Test
    void 항목과_글자가_겹치는_문단만_골라낸다() {
        List<String> matched = ClauseParagraphs.overlapping(CLAUSE, "카카오톡 기프티콘 구매");

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0)).startsWith("⑥");
    }

    @Test
    void 흔한_글자_조각만_겹치면_겹친_것으로_보지_않는다() {
        // "학생회비"·"사용"은 거의 모든 조항에 나오는 조각이다.
        assertThat(ClauseParagraphs.overlapping(CLAUSE, "학생회비 사용")).isEmpty();
    }

    @Test
    void 번호_목록의_호도_문단으로_본다() {
        String list = String.join("\n",
                "제15조의3(사용 제한)",
                "① 다음 각 호에 대한 학생회비 사용을 금지한다.",
                "1. 학생회 회식",
                "2. 학생회 LT",
                "3. 1박 이상의 사전답사");

        assertThat(ClauseParagraphs.overlapping(list, "사전답사 숙박비")).containsExactly("3. 1박 이상의 사전답사");
    }

    @Test
    void 문단이_하나뿐인_조항은_조항_전체와_같으므로_비워_둔다() {
        assertThat(ClauseParagraphs.overlapping("제1조(목적) 이 규정은 지출 절차를 정한다.", "지출 절차")).isEmpty();
    }

    @Test
    void 최대_세_문단까지만_돌려준다() {
        String many = String.join("\n", "제1조(예)", "① 간식 구매", "② 간식 보관", "③ 간식 영수증", "④ 간식 폐기");

        assertThat(ClauseParagraphs.overlapping(many, "간식")).hasSize(3);
    }
}
