package kr.dku.councilflow.decision;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 업무보드에서 회의 책임자가 하는 일: 내용·담당·기한 수정, 업무 추가, 업무 취소. */
class TaskManagementTest extends IntegrationTest {

    static final String TRANSCRIPT = """
            회장: 학생회실 대청소를 다음 주 금요일에 하기로 합시다.
            홍보부장: 청소 공지는 홍보부가 올리겠습니다.
            기획부장: 쓰레기봉투는 기획부가 준비하겠습니다.
            """;
    static final String AI_RESULT = """
            {"agendas":[{"agenda":"학생회실 정리","decisions":[{"summary":"다음 주 금요일 학생회실 대청소","decision_status":"CONFIRMED",
              "source_segment_ids":["S1"],"tasks":[
                {"title":"청소 공지","owner_department":"홍보부","due_date":"2026-10-16","source_segment_ids":["S2"]},
                {"title":"쓰레기봉투 준비","owner_department":"기획부","due_date":"","source_segment_ids":["S3"]},
                {"title":"엉뚱한 후보","owner_department":"","due_date":"","source_segment_ids":["S1"]}]}]}]}
            """;

    @Autowired
    JobRunner jobRunner;

    String chair;
    long cse;
    long meetingId;
    String proposals;

    @BeforeEach
    void analyzedMeeting() throws Exception {
        chair = bearer("ce");
        cse = orgId("컴퓨터공학과 학생회");
        meetingId = createMeeting("ce", cse, TRANSCRIPT);
        CLOVA.enqueue(FakeClovaServer.ok(AI_RESULT));
        requestAnalysis("ce", meetingId);
        jobRunner.runOnce();
        proposals = proposals();
    }

    private String proposals() throws Exception {
        return mvc.perform(get("/api/meetings/{id}/proposals", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andReturn().getResponse().getContentAsString();
    }

    private long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private long departmentId(String name) {
        List<Number> ids = JsonPath.read(proposals, "$.departments[?(@.name == '" + name + "')].id");
        return ids.get(0).longValue();
    }

    private ResultActions patchTask(String token, long taskId, long version, Object body) throws Exception {
        return mvc.perform(patch("/api/tasks/{id}", taskId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, version)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private String approve() throws Exception {
        return mvc.perform(post("/api/decisions/{id}/approve", num(proposals, "$.decisions[0].id"))
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .header(HttpHeaders.IF_MATCH, num(proposals, "$.decisions[0].version")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 승인_전에_후보에서_뺀_업무는_승인해도_업무보드에_오르지_않는다() throws Exception {
        long wrongTask = num(proposals, "$.decisions[0].tasks[2].id");
        patchTask(chair, wrongTask, num(proposals, "$.decisions[0].tasks[2].version"), Map.of("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String approved = approve();

        List<String> statuses = JsonPath.read(approved, "$.tasks[*].status");
        org.assertj.core.api.Assertions.assertThat(statuses).containsExactly("ASSIGNED", "ASSIGNED", "CANCELLED");
    }

    @Test
    void 회장은_업무보드에서_내용_담당_기한을_고치고_부서원은_못_고친다() throws Exception {
        approve();
        long task = num(proposals, "$.decisions[0].tasks[0].id");
        String board = mvc.perform(get("/api/orgs/{id}/tasks", cse).header(HttpHeaders.AUTHORIZATION, chair))
                .andReturn().getResponse().getContentAsString();
        List<Number> versions = JsonPath.read(board, "$[?(@.id == " + task + ")].version");
        long version = versions.get(0).longValue();

        patchTask(bearer("ce.pr"), task, version, Map.of("title", "청소 공지(단톡방+인스타)"))
                .andExpect(status().isForbidden());

        long finance = departmentId("재정부");
        patchTask(chair, task, version, Map.of("title", "청소 공지(단톡방+인스타)", "departmentId", finance,
                "dueDate", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("청소 공지(단톡방+인스타)"))
                .andExpect(jsonPath("$.departmentName").value("재정부"))
                .andExpect(jsonPath("$.dueDate").value("2026-10-15"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    void 진행_중인_업무는_담당을_비울_수_없고_완료_취소된_업무는_고칠_수_없다() throws Exception {
        String approved = approve();
        long task = num(approved, "$.tasks[0].id");
        String started = patchTask(chair, task, num(approved, "$.tasks[0].version"), Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        java.util.HashMap<String, Object> clear = new java.util.HashMap<>();
        clear.put("departmentId", null);
        patchTask(chair, task, num(started, "$.version"), clear)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        String cancelled = patchTask(chair, task, num(started, "$.version"), Map.of("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn().getResponse().getContentAsString();
        patchTask(chair, task, num(cancelled, "$.version"), Map.of("title", "되살리기"))
                .andExpect(status().isConflict());
    }

    @Test
    void 업무_취소는_회장만() throws Exception {
        String approved = approve();
        patchTask(bearer("ce.pr"), num(approved, "$.tasks[0].id"), num(approved, "$.tasks[0].version"),
                Map.of("status", "CANCELLED"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 회장은_승인된_결정에_업무를_직접_추가한다() throws Exception {
        approve();
        long decisionId = num(proposals, "$.decisions[0].id");

        // 업무 추가 화면에서 고를 수 있는 결정은 승인된 것만
        mvc.perform(get("/api/orgs/{id}/decisions", cse).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) decisionId)));

        mvc.perform(post("/api/decisions/{id}/tasks", decisionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce.plan"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"청소 도구 빌리기\"}"))
                .andExpect(status().isForbidden());

        String added = mvc.perform(post("/api/decisions/{id}/tasks", decisionId)
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "청소 도구 빌리기",
                                "departmentId", departmentId("기획부"), "dueDate", "2026-10-15"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.evidenceStatus").value("OK"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/orgs/{id}/tasks", cse).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(jsonPath("$[?(@.id == " + num(added, "$.id") + ")].origin", hasItem("MANUAL")));

        // 담당 없이 추가하면 담당 미정
        mvc.perform(post("/api/decisions/{id}/tasks", decisionId)
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"뒷정리 사진 남기기\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNASSIGNED"));
    }

    @Test
    void 승인_전_결정에_추가한_업무는_후보로_남는다() throws Exception {
        long decisionId = num(proposals, "$.decisions[0].id");
        String added = mvc.perform(post("/api/decisions/{id}/tasks", decisionId)
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"청소 도구 빌리기\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andReturn().getResponse().getContentAsString();

        // 테스트끼리 DB를 같이 쓰므로 제목 대신 id로 확인한다.
        mvc.perform(get("/api/orgs/{id}/tasks", cse).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) num(added, "$.id")))));
    }
}
