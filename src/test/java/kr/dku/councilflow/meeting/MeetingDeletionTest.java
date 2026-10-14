package kr.dku.councilflow.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 회의·업무 완전 삭제(회장만). 회의를 지우면 결정·업무·체크리스트·다른 그룹에 보낸 요청과 제출 결과까지 지워지고,
 * 지출 검토는 남되 결정 연결만 끊긴다. 업무는 어떤 상태든 체크리스트와 함께 지운다.
 */
class MeetingDeletionTest extends IntegrationTest {

    private static final String TRANSCRIPT = """
            회장: 체육대회 준비를 시작합시다.
            기획부장: 기획부가 종목 일정을 짜겠습니다.
            """;
    private static final String AI_RESULT = """
            {"agendas":[{"agenda":"체육대회","decisions":[{"summary":"체육대회를 준비한다.","decision_status":"CONFIRMED",
              "source_segment_ids":["S1"],"tasks":[{"title":"종목 일정 짜기","owner_department":"기획부","due_date":"","source_segment_ids":["S2"]}]}]}]}
            """;

    @Autowired
    JobRunner jobRunner;

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 컴공에 회의를 올려 분석·승인까지 하고 { meetingId, decisionId, taskId, taskVersion } 를 돌려준다. */
    private Map<String, Long> approvedMeeting() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        long meetingId = createMeeting("ce", cse, TRANSCRIPT);
        CLOVA.enqueue(FakeClovaServer.ok(AI_RESULT));
        requestAnalysis("ce", meetingId);
        jobRunner.runOnce();
        String proposals = mvc.perform(get("/api/meetings/{id}/proposals", meetingId)
                        .header(HttpHeaders.AUTHORIZATION, owner))
                .andReturn().getResponse().getContentAsString();
        String approved = mvc.perform(post("/api/decisions/{id}/approve", num(proposals, "$.decisions[0].id"))
                        .header(HttpHeaders.AUTHORIZATION, owner)
                        .header(HttpHeaders.IF_MATCH, num(proposals, "$.decisions[0].version")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Map.of("meetingId", meetingId, "decisionId", num(approved, "$.id"),
                "taskId", num(approved, "$.tasks[0].id"), "taskVersion", num(approved, "$.tasks[0].version"));
    }

    @Test
    void 회의를_지우면_결정_업무_보낸_요청까지_지우고_지출_검토는_연결만_끊는다() throws Exception {
        Map<String, Long> made = approvedMeeting();
        long meetingId = made.get("meetingId");
        long decisionId = made.get("decisionId");
        long taskId = made.get("taskId");
        long sw = orgId("소프트웨어학과 학생회");
        String owner = bearer("ce");

        // 체크리스트 1개
        json(post("/api/tasks/{id}/items", taskId), bearer("ce.plan"), Map.of("title", "종목 후보 정리"))
                .andExpect(status().isCreated());
        // 소프트웨어학과에 업무 요청 → 수락 → 시작 → 결과 제출
        json(post("/api/decisions/{id}/deliveries", decisionId), owner,
                Map.of("targetOrgIds", List.of(sw), "requestType", "COOPERATE"))
                .andExpect(status().isCreated());
        String swOwner = bearer("sw");
        String inbox = mvc.perform(get("/api/orgs/{id}/inbox", sw).header(HttpHeaders.AUTHORIZATION, swOwner))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> received = JsonPath.read(inbox, "$[?(@.decisionId == " + decisionId + ")]");
        long deliveryId = ((Number) received.get(0).get("id")).longValue();
        long deliveryVersion = ((Number) received.get(0).get("version")).longValue();
        json(patch("/api/deliveries/{id}", deliveryId).header(HttpHeaders.IF_MATCH, deliveryVersion), swOwner,
                Map.of("status", "ACCEPTED")).andExpect(status().isOk());
        json(patch("/api/deliveries/{id}", deliveryId).header(HttpHeaders.IF_MATCH, deliveryVersion + 1), swOwner,
                Map.of("status", "IN_PROGRESS")).andExpect(status().isOk());
        json(post("/api/deliveries/{id}/submissions", deliveryId), swOwner,
                Map.of("resultLink", "https://example.com/result", "description", "완료"))
                .andExpect(status().isCreated());
        // 그 결정에서 사람이 요청한 지출 검토(남음)와, 업무 카드에 자동으로 만든 규정 확인(같이 지워짐)
        jdbc.update("INSERT INTO expense_review (org_id, decision_id, item, status, origin, version, created_at, updated_at) "
                + "VALUES (?, ?, '체육대회 물품', 'NEEDS_CHECK', 'MANUAL', 0, NOW(6), NOW(6))", orgId("컴퓨터공학과 학생회"), decisionId);
        jdbc.update("INSERT INTO expense_review (org_id, decision_id, task_id, item, status, origin, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, '체육대회 자동 확인', 'NEEDS_CHECK', 'AUTO', 0, NOW(6), NOW(6))",
                orgId("컴퓨터공학과 학생회"), decisionId, taskId);

        // 지우기 전 영향 범위(회장만)
        mvc.perform(get("/api/meetings/{id}/deletion-impact", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionCount").value(1))
                .andExpect(jsonPath("$.approvedDecisionCount").value(1))
                .andExpect(jsonPath("$.taskCount").value(1))
                .andExpect(jsonPath("$.checklistItemCount").value(1))
                .andExpect(jsonPath("$.deliveryCount").value(1))
                .andExpect(jsonPath("$.receiverGroups[0]").value("소프트웨어학과 학생회"))
                .andExpect(jsonPath("$.expenseReviewCount").value(1))
                .andExpect(jsonPath("$.jobRunning").value(false));

        // 회장만 지울 수 있다(부장, 다른 그룹 회장, 서비스 관리자는 403).
        for (String outsider : List.of("ce.plan", "sw", "admin")) {
            mvc.perform(delete("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(delete("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNoContent());

        // 회의·결정·업무·체크리스트·요청·제출·발언·작업이 모두 사라진다.
        mvc.perform(get("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM decision WHERE id = ?", decisionId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM task WHERE id = ?", taskId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM task_item WHERE task_id = ?", taskId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM delivery WHERE id = ?", deliveryId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM submission WHERE delivery_id = ?", deliveryId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM segment WHERE meeting_id = ?", meetingId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM job WHERE type = 'ANALYZE' AND target_id = ?", meetingId)).isZero();
        // 받은 그룹 수신함에서도 사라진다.
        String after = mvc.perform(get("/api/orgs/{id}/inbox", sw).header(HttpHeaders.AUTHORIZATION, swOwner))
                .andReturn().getResponse().getContentAsString();
        List<Object> left = JsonPath.read(after, "$[?(@.decisionId == " + decisionId + ")]");
        assertThat(left).isEmpty();
        // 사람이 요청한 지출 검토는 남고 결정 연결만 끊긴다. 자동 규정 확인은 함께 지워진다.
        assertThat(count("SELECT COUNT(*) FROM expense_review WHERE item = '체육대회 물품' AND decision_id IS NULL", null))
                .isPositive();
        assertThat(count("SELECT COUNT(*) FROM expense_review WHERE task_id = ?", taskId)).isZero();
        // 무엇을 지웠는지 감사 기록에 남는다.
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'MEETING_DELETED' AND target_id = ?", meetingId))
                .isEqualTo(1);
    }

    @Test
    void 분석이_진행_중이면_회의를_지울_수_없다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        long meetingId = createMeeting("ce", cse, TRANSCRIPT);
        requestAnalysis("ce", meetingId);
        mvc.perform(get("/api/meetings/{id}/deletion-impact", meetingId).header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(jsonPath("$.jobRunning").value(true));
        mvc.perform(delete("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isConflict());
    }

    @Test
    void 회장은_진행_중인_업무도_체크리스트와_함께_지운다() throws Exception {
        Map<String, Long> made = approvedMeeting();
        long taskId = made.get("taskId");
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, made.get("taskVersion")), bearer("ce.plan"),
                Map.of("status", "IN_PROGRESS")).andExpect(status().isOk());
        json(post("/api/tasks/{id}/items", taskId), bearer("ce.plan"), Map.of("title", "종목 후보 정리"))
                .andExpect(status().isCreated());
        // 이 업무 카드에 자동으로 만든 규정 확인
        jdbc.update("INSERT INTO expense_review (org_id, decision_id, task_id, item, status, origin, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, '종목 자동 확인', 'NEEDS_CHECK', 'AUTO', 0, NOW(6), NOW(6))",
                orgId("컴퓨터공학과 학생회"), made.get("decisionId"), taskId);

        mvc.perform(delete("/api/tasks/{id}", taskId).header(HttpHeaders.AUTHORIZATION, bearer("ce.plan")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/tasks/{id}", taskId).header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM task WHERE id = ?", taskId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM task_item WHERE task_id = ?", taskId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM expense_review WHERE task_id = ?", taskId)).isZero();
        String board = mvc.perform(get("/api/orgs/{id}/tasks", orgId("컴퓨터공학과 학생회"))
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andReturn().getResponse().getContentAsString();
        List<Object> found = JsonPath.read(board, "$[?(@.id == " + taskId + ")]");
        assertThat(found).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'TASK_DELETED' AND target_id = ?", taskId))
                .isEqualTo(1);
    }

    private long count(String sql, Long arg) {
        Long value = arg == null ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, arg);
        return value == null ? 0 : value;
    }
}
