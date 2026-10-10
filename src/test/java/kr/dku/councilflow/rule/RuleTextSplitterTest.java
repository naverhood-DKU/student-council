package kr.dku.councilflow.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.dku.councilflow.rule.RuleTextSplitter.Kind;
import kr.dku.councilflow.rule.RuleTextSplitter.Piece;
import kr.dku.councilflow.rule.RuleTextSplitter.Result;
import org.junit.jupiter.api.Test;

/** "제n조" 분리 규칙. 스프링 없이 글자만으로 확인한다. */
class RuleTextSplitterTest {

    private static final String SAMPLE = String.join("\n",
            "단국대학교 감사규정",
            "",
            "제1장 총칙",
            "",
            "제1조(목적) 이 규정은 감사에 관한 사항을 정한다.",
            "",
            "- 2 -",
            "",
            "제2조(정의) 이 규정에서 쓰는 말은 다음과 같다.",
            "  1. 감사: 지출을 살피는 일",
            "제2조의2(감사 대상) 모든 학생자치기구를 대상으로 한다.",
            "제3조 삭제",
            "제4조(재정 보고) 월별로 보고한다. <개정 2024. 8. 21.>",
            "",
            "제2장 징계",
            "제5조(주의) 기한을 넘기면 주의를 준다.",
            "",
            "부",
            "",
            "칙",
            "이 규정은 2024년 8월 21일부터 시행한다.",
            "제6조(부칙 안의 글) 이 줄은 조항으로 나누지 않는다.");

    private static List<Piece> kind(Result result, Kind kind) {
        return result.pieces().stream().filter(p -> p.kind() == kind).toList();
    }

    @Test
    void 조항으로_나누고_이어_붙이면_원문과_같다() {
        Result result = RuleTextSplitter.split(SAMPLE, "단국대학교 감사규정");

        assertThat(result.roundtripOk()).isTrue();
        assertThat(result.bodyStartFound()).isTrue();
        assertThat(result.removedPageLines()).isEqualTo(1);
        assertThat(kind(result, Kind.ARTICLE)).extracting(Piece::label)
                .containsExactly("제1조", "제2조", "제2조의2", "제3조", "제4조", "제5조");
        assertThat(kind(result, Kind.CHAPTER)).extracting(Piece::label).containsExactly("제1장 총칙", "제2장 징계");
    }

    @Test
    void 조항_본문은_글자_그대로_두고_쪽번호_줄만_걷어낸다() {
        Result result = RuleTextSplitter.split(SAMPLE, "단국대학교 감사규정");

        Piece second = kind(result, Kind.ARTICLE).get(1);
        assertThat(second.text()).isEqualTo("제2조(정의) 이 규정에서 쓰는 말은 다음과 같다.\n  1. 감사: 지출을 살피는 일");
        assertThat(result.body()).doesNotContain("- 2 -");
    }

    @Test
    void 장과_절_정보가_조항에_붙고_삭제_조문에_표시한다() {
        Result result = RuleTextSplitter.split(SAMPLE, "단국대학교 감사규정");

        Piece first = kind(result, Kind.ARTICLE).get(0);
        assertThat(first.chapter()).isEqualTo("제1장 총칙");
        Piece deleted = kind(result, Kind.ARTICLE).get(3);
        assertThat(deleted.title()).isEqualTo("삭제");
        assertThat(deleted.flags()).contains("삭제된 조문");
        Piece last = kind(result, Kind.ARTICLE).get(5);
        assertThat(last.chapter()).isEqualTo("제2장 징계");
    }

    @Test
    void 쪼개진_부칙_표시를_찾고_부칙_안의_제목줄은_조항으로_보지_않는다() {
        Result result = RuleTextSplitter.split(SAMPLE, "단국대학교 감사규정");

        List<Piece> supplementary = kind(result, Kind.SUPPLEMENTARY);
        assertThat(supplementary).hasSize(1);
        assertThat(supplementary.get(0).text()).contains("2024년 8월 21일부터 시행").contains("제6조(부칙 안의 글)");
        assertThat(kind(result, Kind.ARTICLE)).extracting(Piece::label).doesNotContain("제6조");
    }

    @Test
    void 목차는_건너뛰고_마지막_제목줄부터_본문으로_본다() {
        String withToc = "단국대학교 감사규정\n제1조 목적 ..... 1\n제2조 정의 ..... 2\n\n" + SAMPLE;

        Result result = RuleTextSplitter.split(withToc, "단국대학교 감사규정");

        assertThat(kind(result, Kind.ARTICLE)).extracting(Piece::label)
                .containsExactly("제1조", "제2조", "제2조의2", "제3조", "제4조", "제5조");
    }

    @Test
    void 기준_줄을_못_찾으면_알리고_처음부터_나눈다() {
        Result result = RuleTextSplitter.split(SAMPLE, "없는 제목");

        assertThat(result.bodyStartFound()).isFalse();
        assertThat(result.roundtripOk()).isTrue();
    }

    @Test
    void 조_번호가_거꾸로_가면_표시한다() {
        Result result = RuleTextSplitter.split("규정\n제3조(가) 가\n제2조(나) 나\n제2조(다) 다", "규정");

        List<Piece> articles = kind(result, Kind.ARTICLE);
        assertThat(articles.get(1).flags()).anyMatch(f -> f.contains("거꾸로"));
        assertThat(articles.get(2).flags()).anyMatch(f -> f.contains("중복"));
    }

    @Test
    void 조항이_없는_글은_머리말_하나로_남고_이어_붙여도_같다() {
        Result result = RuleTextSplitter.split("그냥 문서\n내용", "그냥 문서");

        assertThat(kind(result, Kind.ARTICLE)).isEmpty();
        assertThat(result.pieces()).hasSize(1);
        assertThat(result.roundtripOk()).isTrue();
    }
}
