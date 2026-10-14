package kr.dku.councilflow.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 담당 부서: 회의에서 말한 담당이 먼저(근거 발언에 실제로 있어야 함), 말하지 않았으면 AI가 업무 내용으로 고른 부서("AI 지정"과 이유).
 * AI가 없는 부서를 고르면 쓰지 않고 키워드 추천으로 대신한다. 회장이 바꾸면 "AI 지정" 표시가 사라진다.
 */
class AiDepartmentPickTest extends IntegrationTest {

    private static final String TRANSCRIPT = """
            회장: 축제 부스 준비를 시작합시다.
            회장: 부스 포스터를 만들어서 인스타에 올리고, 물품 목록은 기획부가 정리합시다.
            회장: 영수증 정리도 누가 해 줘야 해요.
            """;
    private static final String AI_RESULT = """
            {"agendas":[{"agenda":"축제 부스","decisions":[{"summary":"축제 부스를 준비한다.","decision_status":"CONFIRMED",
              "source_segment_ids":["S1"],"tasks":[
                {"title":"부스 포스터 제작·게시","owner_department":"","department":"홍보부",
                 "department_reason":"포스터 제작과 SNS 게시는 홍보부 역할이다.","due_date":"","source_segment_ids":["S2"]},
                {"title":"부스 물품 목록 정리","owner_department":"기획부","department":"홍보부",
                 "department_reason":"잘못 고른 예","due_date":"","source_segment_ids":["S2"]},
                {"title":"영수증 정리","owner_department":"","department":"총무부",
                 "department_reason":"없는 부서","due_date":"","source_segment_ids":["S3"]},
                {"title":"영수증 제출","owner_department":"재정부","department":"",
                 "department_reason":"","due_date":"","source_segment_ids":["S3"]}]}]}]}
            """;

    @Autowired
    JobRunner jobRunner;

    @Test
    void 말하지_않은_담당은_AI가_고르고_회장이_바꾸면_AI_표시가_사라진다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        long meetingId = createMeeting("ce", cse, TRANSCRIPT);
        CLOVA.enqueue(FakeClovaServer.ok(AI_RESULT));
        requestAnalysis("ce", meetingId);
        jobRunner.runOnce();

        // AI 에게 부서 이름과 역할 키워드를 같이 보낸다.
        List<FakeClovaServer.Recorded> sent = CLOVA.requests();
        assertThat(sent.get(sent.size() - 1).body().toString())
                .contains("등록된 부서(이름: 역할 키워드)").contains("홍보부(홍보, SNS");

        String proposals = mvc.perform(get("/api/meetings/{id}/proposals", meetingId)
                        .header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                // ① 말하지 않은 담당: AI가 고른 홍보부 + 이유
                .andExpect(jsonPath("$.decisions[0].tasks[0].departmentName").value("홍보부"))
                .andExpect(jsonPath("$.decisions[0].tasks[0].departmentByAi").value(true))
                .andExpect(jsonPath("$.decisions[0].tasks[0].departmentReason").value("포스터 제작과 SNS 게시는 홍보부 역할이다."))
                // ② 회의에서 말한 담당(기획부)이 AI 선택보다 먼저
                .andExpect(jsonPath("$.decisions[0].tasks[1].departmentName").value("기획부"))
                .andExpect(jsonPath("$.decisions[0].tasks[1].departmentByAi").value(false))
                // ③ 없는 부서를 고르면 쓰지 않고 비운다(키워드 추천은 따로)
                .andExpect(jsonPath("$.decisions[0].tasks[2].departmentId").doesNotExist())
                .andExpect(jsonPath("$.decisions[0].tasks[2].departmentByAi").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> notes = JsonPath.read(proposals, "$.decisions[0].tasks[2].validationNotes");
        assertThat(notes).anyMatch(n -> n.contains("총무부") && n.contains("등록된 부서가 아니라"));

        // ④ 회의에서 말하지 않은 부서를 "말한 담당"으로 적으면(근거 발언에 그 말이 없음) AI가 고른 담당으로 다룬다.
        mvc.perform(get("/api/meetings/{id}/proposals", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$.decisions[0].tasks[3].departmentName").value("재정부"))
                .andExpect(jsonPath("$.decisions[0].tasks[3].departmentByAi").value(true))
                .andExpect(jsonPath("$.decisions[0].tasks[3].ownerDepartmentText").doesNotExist());
        List<String> claimNotes = JsonPath.read(proposals, "$.decisions[0].tasks[3].validationNotes");
        assertThat(claimNotes).anyMatch(n -> n.contains("근거 발언에 그 말이 없어"));

        // 회장이 승인 전에 다른 부서로 바꾸면 "AI 지정" 표시와 이유가 사라진다.
        long posterTaskId = ((Number) JsonPath.read(proposals, "$.decisions[0].tasks[0].id")).longValue();
        long posterVersion = ((Number) JsonPath.read(proposals, "$.decisions[0].tasks[0].version")).longValue();
        List<Number> planning = JsonPath.read(proposals, "$.departments[?(@.name == '기획부')].id");
        mvc.perform(patch("/api/tasks/{id}", posterTaskId)
                        .header(HttpHeaders.AUTHORIZATION, owner)
                        .header(HttpHeaders.IF_MATCH, posterVersion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("departmentId", planning.get(0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentName").value("기획부"))
                .andExpect(jsonPath("$.departmentByAi").value(false))
                .andExpect(jsonPath("$.departmentReason").doesNotExist());

        // 승인하면 담당이 있는 업무는 바로 "담당 지정"(ASSIGNED)이 된다.
        long decisionId = ((Number) JsonPath.read(proposals, "$.decisions[0].id")).longValue();
        long decisionVersion = ((Number) JsonPath.read(proposals, "$.decisions[0].version")).longValue();
        mvc.perform(post("/api/decisions/{id}/approve", decisionId)
                        .header(HttpHeaders.AUTHORIZATION, owner)
                        .header(HttpHeaders.IF_MATCH, decisionVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.tasks[1].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.tasks[2].status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.tasks[3].status").value("ASSIGNED"));
    }
}
