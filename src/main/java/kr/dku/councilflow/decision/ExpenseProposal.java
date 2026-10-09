package kr.dku.councilflow.decision;

import jakarta.persistence.AttributeConverter;
import kr.dku.councilflow.common.JsonColumns;

/** 결정에 딸린 지출 언급. 단계 4 지출 절차 안내에서 미리 채우는 데 쓴다. 모르는 값은 null. */
public record ExpenseProposal(String item, Long amount, String fundSource) {

    public static class Converter implements AttributeConverter<ExpenseProposal, String> {
        @Override
        public String convertToDatabaseColumn(ExpenseProposal attribute) {
            return JsonColumns.write(attribute);
        }

        @Override
        public ExpenseProposal convertToEntityAttribute(String dbData) {
            return JsonColumns.read(dbData, ExpenseProposal.class);
        }
    }
}
