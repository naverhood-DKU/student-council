package kr.dku.councilflow.auth;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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

/**
 * 권한 개편: 그룹마다 owner(회장)·editor(부장)·viewer(부원), 서비스 관리자는 그룹을 만들고 owner 를 부여한다.
 * - editor: 자기 부서 업무의 시작·결과 제출만
 * - viewer: 조회만
 * - owner: 다른 모든 그룹에 업무 요청
 * - 서비스 관리자: 모든 그룹 읽기 전용 열람, 그룹 업무는 못 바꿈
 */
class GroupRolesTest extends IntegrationTest {

    static final String TRANSCRIPT = """
            회장: 다음 주 금요일까지 학과 신문 원고를 모읍시다.
            기획부장: 기획부가 원고를 모으겠습니다.
            """;
    static final String AI_RESULT = """
            {"agendas":[{"agenda":"학과 신문","decisions":[{"summary":"학과 신문 원고를 모은다.","decision_status":"CONFIRMED",
              "source_segment_ids":["S1"],"tasks":[{"title":"원고 모으기","owner_department":"기획부","due_date":"","source_segment_ids":["S2"]}]}]}]}
            """;

    @Autowired
    JobRunner jobRunner;

    /** 컴공에 승인된 결정과 기획부 업무 하나를 만들고 { decisionId, taskId, meetingId } 를 돌려준다. */
    private Map<String, Long> approvedCseDecision() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        long meetingId = createMeeting("ce", cse, TRANSCRIPT);
        CLOVA.enqueue(FakeClovaServer.ok(AI_RESULT));
        requestAnalysis("ce", meetingId);
        jobRunner.runOnce();
        String proposals = mvc.perform(get("/api/meetings/{id}/proposals", meetingId)
                        .header(HttpHeaders.AUTHORIZATION, owner))
                .andReturn().getResponse().getContentAsString();
        long decisionId = num(proposals, "$.decisions[0].id");
        String approved = mvc.perform(post("/api/decisions/{id}/approve", decisionId)
                        .header(HttpHeaders.AUTHORIZATION, owner)
                        .header(HttpHeaders.IF_MATCH, num(proposals, "$.decisions[0].version")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Map.of("decisionId", decisionId, "taskId", num(approved, "$.tasks[0].id"),
                "taskVersion", num(approved, "$.tasks[0].version"), "meetingId", meetingId);
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private String login(String loginId, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", loginId, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    @Test
    void viewer는_조회만_하고_editor는_자기_부서_업무만_진행한다() throws Exception {
        Map<String, Long> made = approvedCseDecision();
        long cse = orgId("컴퓨터공학과 학생회");
        long taskId = made.get("taskId");
        long version = made.get("taskVersion");

        // viewer(기획부 부원): 업무보드·회의 결과는 보지만 같은 부서 업무도 시작할 수 없다.
        String viewer = bearer("ce.member");
        mvc.perform(get("/api/orgs/{id}/tasks", cse).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/meetings/{id}/proposals", made.get("meetingId")).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk());
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, version), viewer,
                Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isForbidden());
        json(post("/api/meetings"), viewer, Map.of("orgId", cse, "title", "회의", "heldAt", "2026-10-08T09:00:00Z",
                "transcript", "회장: 시작"))
                .andExpect(status().isForbidden());

        // editor(기획부장): 내용은 못 고치고, 자기 부서 업무를 시작한다.
        String editor = bearer("ce.plan");
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, version), editor,
                Map.of("title", "바꾼 제목"))
                .andExpect(status().isForbidden());
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, version), editor,
                Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 다른 부서 editor(홍보부장)는 기획부 업무를 진행할 수 없다.
        json(patch("/api/tasks/{id}", taskId).header(HttpHeaders.IF_MATCH, version + 1), bearer("ce.pr"),
                Map.of("status", "SUBMITTED"))
                .andExpect(status().isForbidden());
    }

    @Test
    void owner는_단계와_상관없이_다른_그룹에_요청한다() throws Exception {
        Map<String, Long> made = approvedCseDecision();
        long sw = orgId("소프트웨어학과 학생회");
        long union = orgId("총학생회");
        long college = orgId("AI 융합대학 학생회");

        // 학과 → 같은 단계 학과, 상위 단과대·총학 모두 가능
        json(post("/api/decisions/{id}/deliveries", made.get("decisionId")), bearer("ce"),
                Map.of("targetOrgIds", List.of(sw, college, union), "requestType", "COOPERATE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets", hasSize(3)));
        // editor 는 요청을 보낼 수 없다.
        json(post("/api/decisions/{id}/deliveries", made.get("decisionId")), bearer("ce.plan"),
                Map.of("targetOrgIds", List.of(sw), "requestType", "INFORM"))
                .andExpect(status().isForbidden());

        // 받은 그룹(소프트웨어)의 owner 가 수락한다. 받은 그룹 viewer 는 수락할 수 없다.
        String inbox = mvc.perform(get("/api/orgs/{id}/inbox", sw).header(HttpHeaders.AUTHORIZATION, bearer("sw")))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(inbox, "$[?(@.decisionId == " + made.get("decisionId") + ")].id");
        List<Number> versions = JsonPath.read(inbox, "$[?(@.decisionId == " + made.get("decisionId") + ")].version");
        json(patch("/api/deliveries/{id}", ids.get(0).longValue()).header(HttpHeaders.IF_MATCH, versions.get(0).longValue()),
                bearer("sw.member"), Map.of("status", "ACCEPTED"))
                .andExpect(status().isForbidden());
        json(patch("/api/deliveries/{id}", ids.get(0).longValue()).header(HttpHeaders.IF_MATCH, versions.get(0).longValue()),
                bearer("sw"), Map.of("status", "ACCEPTED"))
                .andExpect(status().isOk());
    }

    @Test
    void 서비스_관리자는_모든_그룹을_읽기만_한다() throws Exception {
        Map<String, Long> made = approvedCseDecision();
        long cse = orgId("컴퓨터공학과 학생회");
        String admin = bearer("admin");

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.serviceAdmin").value(true))
                .andExpect(jsonPath("$.memberships", hasSize(0)));
        mvc.perform(get("/api/orgs/{id}", cse).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminView").value(true))
                .andExpect(jsonPath("$.myRole").doesNotExist());
        mvc.perform(get("/api/meetings/{id}/proposals", made.get("meetingId")).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orgs/{id}/tasks", cse).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/decisions/{id}/delivery-summary", made.get("decisionId"))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());

        // 쓰기는 모두 403
        json(patch("/api/tasks/{id}", made.get("taskId")).header(HttpHeaders.IF_MATCH, made.get("taskVersion")), admin,
                Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isForbidden());
        json(post("/api/meetings"), admin, Map.of("orgId", cse, "title", "회의", "heldAt", "2026-10-08T09:00:00Z",
                "transcript", "회장: 시작"))
                .andExpect(status().isForbidden());
        json(post("/api/decisions/{id}/deliveries", made.get("decisionId")), admin,
                Map.of("targetOrgIds", List.of(orgId("총학생회")), "requestType", "INFORM"))
                .andExpect(status().isForbidden());

        // 관리자 API는 관리자만
        mvc.perform(get("/api/admin/groups").header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/groups").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("컴퓨터공학과 학생회")));
    }

    @Test
    void 관리자가_그룹을_만들고_owner를_부여하면_owner가_구성원을_관리한다() throws Exception {
        String admin = bearer("admin");
        long union = orgId("총학생회");

        String group = json(post("/api/admin/groups"), admin,
                Map.of("name", "SW융합대학 학생회", "joinCode", "swcollege", "type", "COLLEGE", "parentId", union,
                        "term", "2026"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentName").value("총학생회"))
                .andExpect(jsonPath("$.joinCode").value("swcollege"))
                .andReturn().getResponse().getContentAsString();
        long groupId = num(group, "$.id");

        // 새 아이디면 이름과 8자 이상 초기 비밀번호가 필요하다.
        json(post("/api/admin/groups/{id}/owners", groupId), admin,
                Map.of("loginId", "swc", "name", "SW융합 회장", "initialPassword", "short"))
                .andExpect(status().isBadRequest());
        json(post("/api/admin/groups/{id}/owners", groupId), admin,
                Map.of("loginId", "swc", "name", "SW융합 회장", "initialPassword", "first-pass-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners[*].loginId", hasItem("swc")));

        String owner = login("swc", "first-pass-1");
        String detail = mvc.perform(get("/api/orgs/{id}", groupId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.departments", hasSize(3)))
                .andReturn().getResponse().getContentAsString();
        List<Number> planning = JsonPath.read(detail, "$.departments[?(@.name == '기획부')].id");

        // owner 는 editor·viewer 만 초대한다. editor 는 부서가 있어야 한다.
        json(post("/api/orgs/{id}/members", groupId), owner,
                Map.of("loginId", "swc.vice", "name", "부회장", "initialPassword", "vice-pass-1", "role", "OWNER"))
                .andExpect(status().isForbidden());
        json(post("/api/orgs/{id}/members", groupId), owner,
                Map.of("loginId", "swc.plan", "name", "기획부장", "initialPassword", "plan-pass-1", "role", "EDITOR"))
                .andExpect(status().isBadRequest());
        String added = json(post("/api/orgs/{id}/members", groupId), owner,
                Map.of("loginId", "swc.plan", "name", "기획부장", "initialPassword", "plan-pass-1", "role", "EDITOR",
                        "departmentId", planning.get(0).longValue()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentName").value("기획부"))
                .andReturn().getResponse().getContentAsString();
        long membershipId = num(added, "$.membershipId");

        // 다른 그룹 owner 는 이 그룹 구성원을 바꿀 수 없다.
        json(patch("/api/memberships/{id}", membershipId).header(HttpHeaders.IF_MATCH, num(added, "$.version")),
                bearer("ce"), Map.of("role", "VIEWER"))
                .andExpect(status().isForbidden());
        json(patch("/api/memberships/{id}", membershipId).header(HttpHeaders.IF_MATCH, num(added, "$.version")),
                owner, Map.of("role", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // 소속을 끊으면 그 그룹에 바로 못 들어간다.
        String member = login("swc.plan", "plan-pass-1");
        mvc.perform(get("/api/orgs/{id}", groupId).header(HttpHeaders.AUTHORIZATION, member))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/memberships/{id}", membershipId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/orgs/{id}", groupId).header(HttpHeaders.AUTHORIZATION, member))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/members", groupId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$[*].loginId", not(hasItem("swc.plan"))));

        // owner 회수
        long ownerUserId = ((Number) JsonPath.read(
                mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, owner))
                        .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(delete("/api/admin/groups/{id}/owners/{userId}", groupId, ownerUserId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners", hasSize(0)));
        mvc.perform(get("/api/orgs/{id}", groupId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
    }

    @Test
    void 비밀번호를_바꾼다() throws Exception {
        String admin = bearer("admin");
        long cse = orgId("컴퓨터공학과 학생회");
        json(post("/api/orgs/{id}/members", cse), bearer("ce"),
                Map.of("loginId", "ce.new", "name", "새 부원", "initialPassword", "start-pass-1", "role", "VIEWER"))
                .andExpect(status().isCreated());
        String member = login("ce.new", "start-pass-1");

        json(post("/api/me/password"), member, Map.of("currentPassword", "wrong-pass", "newPassword", "next-pass-1"))
                .andExpect(status().isBadRequest());
        json(post("/api/me/password"), member, Map.of("currentPassword", "start-pass-1", "newPassword", "short"))
                .andExpect(status().isBadRequest());
        json(post("/api/me/password"), member, Map.of("currentPassword", "start-pass-1", "newPassword", "next-pass-1"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", "ce.new", "password", "start-pass-1"))))
                .andExpect(status().isUnauthorized());
        login("ce.new", "next-pass-1");
        // 관리자 토큰으로도 남의 비밀번호는 바꿀 수 없다(자기 것만).
        json(post("/api/me/password"), admin, Map.of("currentPassword", "start-pass-1", "newPassword", "x-pass-12"))
                .andExpect(status().isBadRequest());
    }
}
