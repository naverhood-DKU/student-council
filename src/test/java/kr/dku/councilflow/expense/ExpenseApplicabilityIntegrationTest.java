package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 적용 재확인(작업 3 보강): 첫 안내 뒤에 항목마다 "이 조항이 이 지출에 해당하는가"를 모델에게 한 번 더 묻고,
 * 해당하지 않는다는 항목은 체크리스트에서 빼 따로 보여 준다. AI 는 가짜 CLOVA 서버가 준다.
 *
 * 기존 지출 안내 테스트는 AI 호출이 한 번이라고 가정하므로 설정에서 재확인을 끄고, 이 클래스만 켠다.
 */
@TestPropertySource(properties = "app.expense.applicability-check=true")
class ExpenseApplicabilityIntegrationTest extends ExpenseTestSupport {

    @Autowired
    JobRunner jobRunner;

    private static String rule(String marker) {
        return String.join("\n",
                "지출 규정 " + marker + System.nanoTime(),
                // 다른 지출 테스트의 규정과 같은 문장을 쓰면 FULLTEXT 점수가 달라져 그쪽 테스트의 후보 순서가 바뀐다.
                "제1조(용도) " + marker + " 구매는 총무가 먼저 정한다.",
                "제2조(첨부) " + marker + " 구매는 견적서를 붙인다.",
                "제3조(점검) " + marker + " 구매는 감사가 살펴본다.");
    }

    private static String verdicts(String... idAndApplies) {
        List<Object> list = new java.util.ArrayList<>();
        for (int i = 0; i < idAndApplies.length; i += 2) {
            Map<String, Object> verdict = new LinkedHashMap<>();
            verdict.put("id", idAndApplies[i]);
            verdict.put("applies", idAndApplies[i + 1]);
            verdict.put("quote_supports", "YES");
            list.add(verdict);
        }
        return JSON.writeValueAsString(Map.of("verdicts", list));
    }

    @Test
    void 해당하지_않는다는_항목은_체크리스트에서_빠지고_따로_표시된다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("쿠쿠랄랄"));
        String created = requestReview(owner, ce, "쿠쿠랄랄", 300000L);
        long id = num(created, "$.id");

        CLOVA.enqueue(FakeClovaServer.ok(ai(
                List.of(cite("회장의 사전 승인", candidateText(created, 0), "C1"),
                        cite("재정부장 확인", candidateText(created, 1), "C2")),
                List.of(), List.of(), List.of(), "후보 조항을 직접 확인하세요.")));
        CLOVA.enqueue(FakeClovaServer.ok(verdicts("A1", "NO", "A2", "YES")));
        assertThat(jobRunner.runOnce()).isTrue();

        view(owner, id)
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.checklist", hasSize(1)))
                .andExpect(jsonPath("$.checklist[0].text").value("재정부장 확인"))
                .andExpect(jsonPath("$.guidance.setAside", hasSize(1)))
                .andExpect(jsonPath("$.guidance.setAside[0].text").value("회장의 사전 승인"))
                .andExpect(jsonPath("$.guidance.setAside[0].kind").value("APPROVAL"))
                .andExpect(jsonPath("$.guidance.setAside[0].reason").value("NOT_APPLICABLE"))
                // 요약 안내문은 따로 둔 항목을 담고 있을 수 있어 표시하지 않는다.
                .andExpect(jsonPath("$.guidance.note").doesNotExist())
                .andExpect(jsonPath("$.guidance.droppedNotes", hasItem(containsString("따로 표시했습니다"))));

        assertThat(CLOVA.requests()).hasSize(2);
        String second = CLOVA.requests().get(1).body().toString();
        assertThat(second).contains("<항목>").contains("A1").contains("A2");
        // 조항 이름(C1)이나 DB id 는 두 번째 요청에도 나가지 않는다. 항목 이름(A1)과 조항 라벨만 쓴다.
        assertThat(second).doesNotContain("[C1]");
    }

    @Test
    void 재확인_호출이_실패해도_작업은_성공하고_확인하지_못했다고_알린다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("큐큐팡팡"));
        String created = requestReview(owner, ce, "큐큐팡팡", 300000L);
        long id = num(created, "$.id");

        CLOVA.enqueue(FakeClovaServer.ok(oneApproval(created)));
        CLOVA.enqueue(FakeClovaServer.error(500, "50000"));
        assertThat(jobRunner.runOnce()).isTrue();

        view(owner, id)
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.status").value("PROCEDURE_FOUND"))
                .andExpect(jsonPath("$.checklist", hasSize(1)))
                .andExpect(jsonPath("$.guidance.setAside", hasSize(0)))
                .andExpect(jsonPath("$.guidance.droppedNotes", hasItem(containsString("다시 확인하지 못했습니다"))));
    }

    @Test
    void 재확인_응답_형식이_틀리면_한_번만_다시_묻고_그래도_틀리면_첫_결과를_둔다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("튜튜뽕뽕"));
        String created = requestReview(owner, ce, "튜튜뽕뽕", null);
        long id = num(created, "$.id");

        CLOVA.enqueue(FakeClovaServer.ok(oneApproval(created)));
        CLOVA.enqueue(FakeClovaServer.ok("{\"nope\":[]}"));
        CLOVA.enqueue(FakeClovaServer.ok("not json"));
        assertThat(jobRunner.runOnce()).isTrue();

        assertThat(CLOVA.requests()).hasSize(3);
        view(owner, id)
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.checklist", hasSize(1)))
                .andExpect(jsonPath("$.guidance.droppedNotes", hasItem(containsString("다시 확인하지 못했습니다"))));
    }
}
