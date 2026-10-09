package kr.dku.councilflow.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;

/**
 * 업무 체크리스트: 회장이나 담당 부서 부장이 업무를 잘게 나눠 부서 사람에게 맡기고, 맡은 사람이 완료를 체크한다.
 */
class TaskChecklistTest extends IntegrationTest {

    private static final String TRANSCRIPT = """
            회장: 학과 신문을 이번 달 안에 냅시다.
            기획부장: 기획부가 원고를 모으겠습니다.
            """;
    private static final String AI_RESULT = """
            {"agendas":[{"agenda":"학과 신문","decisions":[{"summary":"학과 신문을 낸다.","decision_status":"CONFIRMED",
              "source_segment_ids":["S1"],"tasks":[{"title":"신문 원고 모으기","owner_department":"기획부","due_date":"","source_segment_ids":["S2"]}]}]}]}
            """;

    @Autowired
    JobRunner jobRunner;

    /** 컴공에 기획부 담당 승인 업무를 하나 만들고 { taskId, taskVersion } 를 돌려준다. */
    private Map<String, Long> approvedPlanningTask() throws Exception {
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
                .andExpect(jsonPath("$.tasks[0].status").value("ASSIGNED"))
                .andReturn().getResponse().getContentAsString();
        return Map.of("taskId", num(approved, "$.tasks[0].id"), "taskVersion", num(approved, "$.tasks[0].version"));
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private long userId(String loginId) throws Exception {
        String me = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(loginId)))
                .andReturn().getResponse().getContentAsString();
        return num(me, "$.id");
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    @Test
    void 부장이_부서_사람에게_맡기고_맡은_사람이_완료를_체크한다() throws Exception {
        long taskId = approvedPlanningTask().get("taskId");
        long viewerId = userId("ce.member");   // 기획부 부원
        long prLeadId = userId("ce.pr");       // 홍보부장
        String items = "/api/tasks/{id}/items";

        // 부원과 다른 부서 부장은 체크리스트를 만들 수 없다.
        json(post(items, taskId), bearer("ce.member"), Map.of("title", "원고 모으기"))
                .andExpect(status().isForbidden());
        json(post(items, taskId), bearer("ce.pr"), Map.of("title", "원고 모으기"))
                .andExpect(status().isForbidden());
        // 담당 부서 사람이 아니면 맡길 수 없다.
        json(post(items, taskId), bearer("ce.plan"), Map.of("title", "원고 모으기", "assigneeUserId", prLeadId))
                .andExpect(status().isBadRequest());

        String created = json(post(items, taskId), bearer("ce.plan"),
                Map.of("title", "1학년 원고 3편 모으기", "assigneeUserId", viewerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeName").value("컴공 부원"))
                .andExpect(jsonPath("$.done").value(false))
                .andReturn().getResponse().getContentAsString();
        long itemId = num(created, "$.id");
        long version = num(created, "$.version");

        // 업무보드에 체크리스트가 붙어서 나온다.
        String board = mvc.perform(get("/api/orgs/{id}/tasks", orgId("컴퓨터공학과 학생회"))
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce.member")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(board, "$[?(@.id == " + taskId + ")].items[*].title");
        assertThat(titles).containsExactly("1학년 원고 3편 모으기");

        // 맡은 부원은 완료 체크만 할 수 있다.
        json(patch("/api/task-items/{id}", itemId).header(HttpHeaders.IF_MATCH, version), bearer("ce.member"),
                Map.of("title", "바꾼 할 일"))
                .andExpect(status().isForbidden());
        json(patch("/api/task-items/{id}", itemId).header(HttpHeaders.IF_MATCH, version), bearer("ce.member"),
                Map.of("done", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.doneAt").isNotEmpty());
        // 맡지 않은 다른 부서 부장은 체크할 수 없다. 화면이 본 버전이 낡았으면 409.
        json(patch("/api/task-items/{id}", itemId).header(HttpHeaders.IF_MATCH, version + 1), bearer("ce.pr"),
                Map.of("done", false))
                .andExpect(status().isForbidden());
        json(patch("/api/task-items/{id}", itemId).header(HttpHeaders.IF_MATCH, version), bearer("ce.plan"),
                Map.of("done", false))
                .andExpect(status().isConflict());

        // 지우기는 회장·담당 부서 부장만
        mvc.perform(delete("/api/task-items/{id}", itemId).header(HttpHeaders.AUTHORIZATION, bearer("ce.member")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/task-items/{id}", itemId).header(HttpHeaders.AUTHORIZATION, bearer("ce.plan")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 담당_부서가_없거나_끝난_업무에는_체크리스트를_쓸_수_없다() throws Exception {
        Map<String, Long> made = approvedPlanningTask();
        long taskId = made.get("taskId");
        long version = made.get("taskVersion");
        String owner = bearer("ce");

        // 담당을 비우면 쓸 수 없다.
        Map<String, Object> clear = new HashMap<>();
        clear.put("departmentId", null);
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, version), owner, clear)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNASSIGNED"));
        json(post("/api/tasks/{id}/items", taskId), owner, Map.of("title", "할 일"))
                .andExpect(status().isConflict());

        // 다시 기획부에 맡기고 끝까지 진행한 뒤에는 바꿀 수 없다.
        long planning = ((Number) JsonPath.<List<Number>>read(
                mvc.perform(get("/api/orgs/{id}", orgId("컴퓨터공학과 학생회")).header(HttpHeaders.AUTHORIZATION, owner))
                        .andReturn().getResponse().getContentAsString(),
                "$.departments[?(@.name == '기획부')].id").get(0)).longValue();
        long v = version + 1;
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, v++), owner, Map.of("departmentId", planning))
                .andExpect(status().isOk());
        json(post("/api/tasks/{id}/items", taskId), owner, Map.of("title", "회장이 직접 만든 할 일"))
                .andExpect(status().isCreated());
        for (String next : List.of("IN_PROGRESS", "SUBMITTED", "DONE")) {
            json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, v++), owner, Map.of("status", next))
                    .andExpect(status().isOk());
        }
        json(post("/api/tasks/{id}/items", taskId), owner, Map.of("title", "늦은 할 일"))
                .andExpect(status().isConflict());
    }
}
