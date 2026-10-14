package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * 회의 분석이 끝나면 돈과 관련된 업무 카드를 규정에서 자동으로 확인하고, 카드에 배지를 단다.
 * 돈과 관련 없는 카드는 확인하지 않는다. AI 는 테스트 안의 가짜 CLOVA 서버가 준다.
 */
@TestPropertySource(properties = "app.rules.auto-check.enabled=true")
class TaskRuleCheckTest extends ExpenseTestSupport {

    @Autowired
    JobRunner jobRunner;

    private static String transcript() {
        return String.join("\n",
                "회장: 오늘 회의를 시작하겠습니다. 물품 구매와 홍보 안건입니다.",
                "회장: 행사 물품은 재정부가 구매하는 것으로 확정합니다.",
                "홍보부장: 포스터 디자인은 홍보부가 맡겠습니다.");
    }

    private static String analysis(String marker) {
        return """
                {"agendas":[{"agenda":"행사 준비","decisions":[
                  {"summary":"%s 행사 물품을 학생회비로 구매한다.","decision_status":"CONFIRMED","source_segment_ids":["S2"],
                   "tasks":[
                     {"title":"%s 행사 물품 구매","owner_department":"재정부","due_date":"","source_segment_ids":["S2"]},
                     {"title":"행사 포스터 디자인","owner_department":"홍보부","due_date":"","source_segment_ids":["S3"]}
                   ],
                   "expense_proposal":{"item":"%s 행사 물품","amount":"150000","fund_source":"학생회비"}}
                ]}]}
                """.formatted(marker, marker, marker);
    }

    private static String rule(String marker) {
        return String.join("\n",
                "지출 규정 " + marker + System.nanoTime(),
                "제1조(사용 제한) " + marker + " 행사에는 학생회비 사용을 금지한다.",
                "제2조(증빙) " + marker + " 지출은 영수증을 첨부한다.");
    }

    @Test
    void 돈과_관련된_카드만_규정을_확인하고_금지_조항이_확인되면_금지_가능성_배지가_붙는다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        String marker = "쭈쭈빵빵";
        registerRule(owner, ce, rule(marker));
        long meetingId = createMeeting("ce", ce, transcript());

        CLOVA.enqueue(FakeClovaServer.ok(analysis(marker)));
        requestAnalysis("ce", meetingId);
        assertThat(jobRunner.runOnce()).isTrue();

        // 구매 카드(재정부 + 구매)는 확인 중, 포스터 카드는 돈과 관련 없어 확인 대상이 아니다.
        String proposals = body(mvc.perform(get("/api/meetings/{id}/proposals", meetingId)
                        .header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions[0].tasks", hasSize(2)))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.badge").value("PENDING"))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.label").value("규정 확인 중"))
                .andExpect(jsonPath("$.decisions[0].tasks[1].ruleCheck").doesNotExist()));
        long purchaseTask = num(proposals, "$.decisions[0].tasks[0].id");
        long posterTask = num(proposals, "$.decisions[0].tasks[1].id");
        long reviewId = num(proposals, "$.decisions[0].tasks[0].ruleCheck.reviewId");

        // 확인 대상이 아닌 카드는 204
        mvc.perform(get("/api/tasks/{id}/rule-check", posterTask).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNoContent());

        // 자동으로 만든 지출 검토: 사람이 요청한 것이 아니고, 결정·업무 카드와 이어져 있다.
        String review = viewBody(owner, reviewId);
        assertThat(num(review, "$.decisionId")).isEqualTo(num(proposals, "$.decisions[0].id"));
        List<String> texts = JsonPath.read(review, "$.candidates[*].text");
        int ban = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains("금지한다")) {
                ban = i;
            }
        }
        assertThat(ban).as("금지 조항이 후보에 있어야 한다").isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT origin FROM expense_review WHERE id = ?", String.class, reviewId))
                .isEqualTo("AUTO");
        assertThat(jdbc.queryForObject("SELECT task_id FROM expense_review WHERE id = ?", Long.class, reviewId))
                .isEqualTo(purchaseTask);

        // AI 안내(EXPENSE 작업) → 서버 검증 → 배지
        CLOVA.enqueue(FakeClovaServer.ok(aiWithRestrictions(List.of(), List.of(),
                List.of(restrict("PROHIBITED", marker + " 행사에는 학생회비를 쓸 수 없다", "학생회비 사용을 금지한다",
                        "C" + (ban + 1))),
                List.of(), List.of(), "조항 원문을 확인하세요.")));
        assertThat(jobRunner.runOnce()).isTrue();

        mvc.perform(get("/api/tasks/{id}/rule-check", purchaseTask).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.badge").value("PROHIBITED"))
                .andExpect(jsonPath("$.label").value("금지 가능성"))
                .andExpect(jsonPath("$.reviewId").value(reviewId))
                .andExpect(jsonPath("$.evidence[0].kind").value("PROHIBITED"))
                .andExpect(jsonPath("$.evidence[0].quote").value("학생회비 사용을 금지한다"))
                .andExpect(jsonPath("$.evidence[0].clauses[0].label").value("제1조"));

        // 카드 목록(proposals)에도 같은 값이 실려 있다.
        mvc.perform(get("/api/meetings/{id}/proposals", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.badge").value("PROHIBITED"));
    }

    @Test
    void 관련_조항이_없으면_규정_확인_안_됨으로_알리고_안전하다고_말하지_않는다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        long meetingId = createMeeting("ce", ce, transcript());

        // 이 낱말이 든 규정이 없다. 카드는 돈과 관련되어 확인 대상이지만 후보 조항이 없다.
        CLOVA.enqueue(FakeClovaServer.ok(analysis("뿌뿌꿍꿍")));
        requestAnalysis("ce", meetingId);
        assertThat(jobRunner.runOnce()).isTrue();

        mvc.perform(get("/api/meetings/{id}/proposals", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.badge").value("UNCHECKED"))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.label").value("규정 확인 안 됨"))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.candidateCount").value(0));
        // AI 안내 작업은 후보가 있을 때만 만든다.
        assertThat(jobRunner.runOnce()).isFalse();
    }

    @Test
    void 회의를_다시_분석하면_옛_후보에서_만든_규정_확인을_정리하고_새로_만든다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        String marker = "꾸꾸까까";
        registerRule(owner, ce, rule(marker));
        long meetingId = createMeeting("ce", ce, transcript());

        CLOVA.enqueue(FakeClovaServer.ok(analysis(marker)));
        requestAnalysis("ce", meetingId);
        assertThat(jobRunner.runOnce()).isTrue();
        long firstReview = num(body(mvc.perform(get("/api/meetings/{id}/proposals", meetingId)
                .header(HttpHeaders.AUTHORIZATION, owner))), "$.decisions[0].tasks[0].ruleCheck.reviewId");
        // 첫 안내 작업은 건너뛴다.
        jdbc.update("UPDATE job SET status = 'FAILED', locked_until = NULL WHERE status IN ('QUEUED', 'RUNNING')");

        CLOVA.enqueue(FakeClovaServer.ok(analysis(marker)));
        requestAnalysis("ce", meetingId);
        assertThat(jobRunner.runOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_review WHERE id = ?", Integer.class, firstReview))
                .isZero();
        mvc.perform(get("/api/meetings/{id}/proposals", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$.decisions[0].tasks[0].ruleCheck.badge").value("PENDING"));
    }
}
