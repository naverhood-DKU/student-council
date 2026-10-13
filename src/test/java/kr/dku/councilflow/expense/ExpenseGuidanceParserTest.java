package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 1단계(형식) 검증. 조항 이름이 맞는지 같은 의미 검증은 ExpenseGuidanceValidatorTest 에서 한다. */
class ExpenseGuidanceParserTest {

    private final ExpenseGuidanceParser parser = new ExpenseGuidanceParser(JsonMapper.builder().build());

    private static final String VALID = """
            {"required_approvals":[{"text":"회장 승인","clause_ids":["C1"],"quote":"회장의 승인"}],
             "required_evidence":[{"text":"영수증","clause_ids":["C2"],"quote":"영수증을 첨부"}],
             "conflicts":[{"description":"기준이 다르다","clause_ids":["C1","C2"]}],
             "missing_facts":["집행 시기"],
             "guidance":"조항을 확인하세요."}
            """;

    @Test
    void 스키마_모양이면_읽는다() throws Exception {
        ParsedGuidance parsed = parser.parse(VALID);

        assertThat(parsed.approvals()).hasSize(1);
        assertThat(parsed.approvals().get(0).clauseNames()).containsExactly("C1");
        assertThat(parsed.evidence().get(0).quote()).isEqualTo("영수증을 첨부");
        assertThat(parsed.conflicts().get(0).clauseNames()).containsExactly("C1", "C2");
        assertThat(parsed.missingFacts()).containsExactly("집행 시기");
        assertThat(parsed.guidance()).isEqualTo("조항을 확인하세요.");
    }

    @Test
    void 코드_블록으로_감싸도_읽는다() throws Exception {
        assertThat(parser.parse("```json\n" + VALID + "\n```").approvals()).hasSize(1);
    }

    @Test
    void 빈_배열과_빈_안내문도_형식으로는_맞다() throws Exception {
        ParsedGuidance parsed = parser.parse("""
                {"required_approvals":[],"required_evidence":[],"conflicts":[],"missing_facts":[],"guidance":""}
                """);

        assertThat(parsed.approvals()).isEmpty();
        assertThat(parsed.guidance()).isEmpty();
    }

    @Test
    void 형식이_틀리면_어느_필드인지만_알린다() {
        assertThatThrownBy(() -> parser.parse("설명입니다"))
                .isInstanceOf(ExpenseGuidanceParser.InvalidResponse.class).hasMessageContaining("JSON");
        assertThatThrownBy(() -> parser.parse("[1,2]"))
                .isInstanceOf(ExpenseGuidanceParser.InvalidResponse.class).hasMessageContaining("객체");
        assertThatThrownBy(() -> parser.parse("""
                {"required_approvals":[],"required_evidence":[],"conflicts":[],"missing_facts":[]}
                """))
                .isInstanceOf(ExpenseGuidanceParser.InvalidResponse.class).hasMessageContaining("guidance");
        assertThatThrownBy(() -> parser.parse("""
                {"required_approvals":[{"text":"x","clause_ids":"C1","quote":"yyyy"}],
                 "required_evidence":[],"conflicts":[],"missing_facts":[],"guidance":""}
                """))
                .isInstanceOf(ExpenseGuidanceParser.InvalidResponse.class).hasMessageContaining("required_approvals[0].clause_ids");
        assertThatThrownBy(() -> parser.parse("""
                {"required_approvals":[],"required_evidence":[],"conflicts":[],"missing_facts":[1],"guidance":""}
                """))
                .isInstanceOf(ExpenseGuidanceParser.InvalidResponse.class).hasMessageContaining("missing_facts");
    }
}
