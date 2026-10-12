package kr.dku.councilflow.expense;

import jakarta.persistence.AttributeConverter;
import java.util.List;
import kr.dku.councilflow.common.JsonColumns;

/**
 * 서버 검증을 통과한 AI 안내 중 expense_review.ai_result 에 저장하는 부분.
 * 필요 승인·필요 증빙은 체크 상태가 있어 expense_checklist_item 에 따로 둔다.
 * 조항 원문과 모델의 응답 원문은 저장하지 않는다(조항 원문은 화면에서 Clause.text 를 읽는다).
 *
 * @param outcome       사람이 검토 완료를 누르기 전의 결과 종류(PROCEDURE_FOUND / POSSIBLE_CONFLICT / NEEDS_CHECK)
 * @param droppedCount  검증에서 걸러낸 항목 수
 * @param droppedNotes  걸러낸 이유 요약(모델이 쓴 글은 넣지 않는다)
 * @param generatedAt   ISO-8601 시각
 */
public record GuidanceResult(String outcome, List<Conflict> conflicts, int droppedCount, List<String> droppedNotes,
                             String generatedAt) {

    /** 후보 조항끼리 다르게 말하는 부분. 어느 쪽이 맞는지는 정하지 않는다. */
    public record Conflict(String description, List<Long> clauseIds) {
    }

    public static class Converter implements AttributeConverter<GuidanceResult, String> {
        @Override
        public String convertToDatabaseColumn(GuidanceResult attribute) {
            return JsonColumns.write(attribute);
        }

        @Override
        public GuidanceResult convertToEntityAttribute(String dbData) {
            return JsonColumns.read(dbData, GuidanceResult.class);
        }
    }
}
