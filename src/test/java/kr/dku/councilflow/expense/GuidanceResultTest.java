package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** DB 에 저장된 ai_result(JSON) 읽기. 이전 버전이 저장한 값도 읽혀야 한다(안 읽히면 지출 검토 목록 전체가 500 이 된다). */
class GuidanceResultTest {

    private final GuidanceResult.Converter converter = new GuidanceResult.Converter();

    @Test
    void restrictions_가_없던_예전_값도_읽는다() {
        String old = """
                {"outcome":"PROCEDURE_FOUND","conflicts":[],"droppedCount":1,"droppedNotes":["이유"],
                 "generatedAt":"2026-10-10T00:00:00Z"}
                """;

        GuidanceResult result = converter.convertToEntityAttribute(old);

        assertThat(result.outcome()).isEqualTo("PROCEDURE_FOUND");
        assertThat(result.restrictionsOrEmpty()).isEmpty();
        assertThat(result.droppedCount()).isEqualTo(1);
    }

    @Test
    void related_가_없던_값은_관련_있다고_본다() {
        String old = """
                {"outcome":"POSSIBLY_RESTRICTED","conflicts":[],
                 "restrictions":[{"kind":"PROHIBITED","text":"학생회 LT","clauseIds":[7],"quote":"사용을 금지한다"}],
                 "droppedCount":0,"droppedNotes":[],"generatedAt":"2026-10-11T00:00:00Z"}
                """;

        GuidanceResult result = converter.convertToEntityAttribute(old);

        assertThat(result.restrictionsOrEmpty()).hasSize(1);
        assertThat(result.restrictionsOrEmpty().get(0).relatedOrTrue()).isTrue();
    }

    @Test
    void 저장했다가_다시_읽으면_같다() {
        GuidanceResult.Restriction restriction =
                new GuidanceResult.Restriction("LIMITED", "한도", java.util.List.of(1L, 2L), "넘지 못한다", false);
        GuidanceResult original = new GuidanceResult("PROCEDURE_FOUND", java.util.List.of(),
                java.util.List.of(restriction), 2, java.util.List.of("메모"), "2026-10-11T00:00:00Z");

        GuidanceResult read = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original));

        assertThat(read).isEqualTo(original);
        assertThat(read.restrictionsOrEmpty().get(0).relatedOrTrue()).isFalse();
    }
}
