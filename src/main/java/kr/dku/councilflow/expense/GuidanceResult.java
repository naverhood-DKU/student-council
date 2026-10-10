package kr.dku.councilflow.expense;

import jakarta.persistence.AttributeConverter;
import java.util.List;
import kr.dku.councilflow.common.JsonColumns;

/**
 * 서버 검증을 통과한 AI 안내 중 expense_review.ai_result 에 저장하는 부분.
 * 필요 승인·필요 증빙은 체크 상태가 있어 expense_checklist_item 에 따로 둔다.
 * 조항 원문과 모델의 응답 원문은 저장하지 않는다(조항 원문은 화면에서 Clause.text 를 읽는다).
 * 예전에 저장된 값에는 restrictions 가 없을 수 있다(null → 없음으로 본다).
 *
 * @param outcome       사람이 검토 완료를 누르기 전의 결과 종류
 * @param restrictions  사용을 금지·제한하는 조항 내용
 * @param droppedCount  검증에서 걸러낸 항목 수
 * @param droppedNotes  걸러내거나 서버가 조정한 이유 요약(모델이 쓴 글은 넣지 않는다)
 * @param generatedAt   ISO-8601 시각
 */
public record GuidanceResult(String outcome, List<Conflict> conflicts, List<Restriction> restrictions,
                             int droppedCount, List<String> droppedNotes, String generatedAt) {

    public static final String PROHIBITED = "PROHIBITED";
    public static final String LIMITED = "LIMITED";

    /** 후보 조항끼리 다르게 말하는 부분. 어느 쪽이 맞는지는 정하지 않는다. */
    public record Conflict(String description, List<Long> clauseIds) {
    }

    /**
     * 사용을 금지(PROHIBITED)하거나 한도·조건을 두는(LIMITED) 조항 내용.
     * quote 는 근거 조항 원문에서 서버가 확인한 구절이다.
     * related 는 이 금지·제한이 지출 항목과 직접 관련 있어 보이는지(서버의 글자 조각 비교)다. 관련 없어 보여도 지우지 않는다.
     */
    public record Restriction(String kind, String text, List<Long> clauseIds, String quote, Boolean related) {

        /**
         * related 가 없던 옛 저장 값은 모두 관련 있다고 본다(Jackson 3 은 빠진 primitive 를 오류로 보므로 Boolean 으로 둔다).
         */
        public boolean relatedOrTrue() {
            return !Boolean.FALSE.equals(related);
        }
    }

    public List<Restriction> restrictionsOrEmpty() {
        return restrictions == null ? List.of() : restrictions;
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
