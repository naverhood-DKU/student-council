package kr.dku.councilflow.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kr.dku.councilflow.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 회원가입과 가입 신청: 가입 코드로 신청하면 그 그룹 회장이 역할(부장·부원)과 부서를 정해 승인한다.
 * 승인 전에는 계정만 있고 그룹 화면은 403이다.
 */
class JoinRequestTest extends IntegrationTest {

    private static final String PASSWORD = "new-password-1234";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String newLoginId() {
        return "join." + System.nanoTime() + "." + SEQ.incrementAndGet();
    }

    private ResultActions signUp(String loginId, String name, String password, String groupCode) throws Exception {
        return mvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of(
                        "loginId", loginId, "name", name, "password", password, "groupCode", groupCode))));
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", loginId, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 회장이 보는 신청 목록에서 그 아이디의 { id, version } */
    private Map<String, Long> pendingRequest(long orgId, String owner, String loginId) throws Exception {
        String list = mvc.perform(get("/api/orgs/{id}/join-requests", orgId).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> found = JsonPath.read(list, "$[?(@.loginId == '" + loginId + "')]");
        assertThat(found).hasSize(1);
        return Map.of("id", ((Number) found.get(0).get("id")).longValue(),
                "version", ((Number) found.get(0).get("version")).longValue());
    }

    private long departmentId(long orgId, String name) throws Exception {
        String org = mvc.perform(get("/api/orgs/{id}", orgId).header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(org, "$.departments[?(@.name == '" + name + "')].id");
        return ids.get(0).longValue();
    }

    @Test
    void 가입_코드로_가입하면_회장이_역할과_부서를_정해_승인한다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String loginId = newLoginId();

        // 코드는 대소문자를 가리지 않는다.
        signUp(loginId, "새 부원", PASSWORD, "CE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupName").value("컴퓨터공학과 학생회"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 로그인은 되지만 소속이 없어 그룹 화면은 403
        String applicant = login(loginId);
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, applicant))
                .andExpect(jsonPath("$.memberships", hasSize(0)))
                .andExpect(jsonPath("$.joinRequests[0].orgName").value("컴퓨터공학과 학생회"))
                .andExpect(jsonPath("$.joinRequests[0].status").value("PENDING"));
        mvc.perform(get("/api/orgs/{id}", cse).header(HttpHeaders.AUTHORIZATION, applicant))
                .andExpect(status().isForbidden());

        // 신청 목록은 그 그룹 회장만 본다.
        mvc.perform(get("/api/orgs/{id}/join-requests", cse).header(HttpHeaders.AUTHORIZATION, bearer("ce.plan")))
                .andExpect(status().isForbidden());
        Map<String, Long> request = pendingRequest(cse, "ce", loginId);
        long planning = departmentId(cse, "기획부");
        String approve = "/api/join-requests/{id}/approve";

        // 다른 그룹 회장·서비스 관리자는 승인할 수 없다.
        json(post(approve, request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version")), bearer("sw"),
                Map.of("role", "VIEWER", "departmentId", planning))
                .andExpect(status().isForbidden());
        json(post(approve, request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version")), bearer("admin"),
                Map.of("role", "VIEWER", "departmentId", planning))
                .andExpect(status().isForbidden());
        // 회장 자리는 줄 수 없고, 부장은 부서가 있어야 하며, If-Match 가 필요하다.
        json(post(approve, request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version")), bearer("ce"),
                Map.of("role", "OWNER"))
                .andExpect(status().isForbidden());
        json(post(approve, request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version")), bearer("ce"),
                Map.of("role", "EDITOR"))
                .andExpect(status().isBadRequest());
        json(post(approve, request.get("id")), bearer("ce"), Map.of("role", "VIEWER", "departmentId", planning))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));

        json(post(approve, request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version")), bearer("ce"),
                Map.of("role", "VIEWER", "departmentId", planning))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // 한 번 처리한 신청은 다시 처리할 수 없다.
        json(post(approve, request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version") + 1), bearer("ce"),
                Map.of("role", "EDITOR", "departmentId", planning))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, applicant))
                .andExpect(jsonPath("$.memberships[0].orgName").value("컴퓨터공학과 학생회"))
                .andExpect(jsonPath("$.memberships[0].role").value("VIEWER"))
                .andExpect(jsonPath("$.memberships[0].departmentName").value("기획부"))
                .andExpect(jsonPath("$.joinRequests", hasSize(0)));
        mvc.perform(get("/api/orgs/{id}", cse).header(HttpHeaders.AUTHORIZATION, applicant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("VIEWER"));
    }

    @Test
    void 잘못된_가입은_막는다() throws Exception {
        signUp(newLoginId(), "이름", PASSWORD, "no-such-group").andExpect(status().isBadRequest());
        signUp(newLoginId(), "이름", PASSWORD, "!!").andExpect(status().isBadRequest());
        signUp(newLoginId(), "이름", "1234", "ce").andExpect(status().isBadRequest());
        signUp("ce", "이름", PASSWORD, "sw")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));
    }

    @Test
    void 거절되면_사유를_보고_다시_신청한다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String loginId = newLoginId();
        signUp(loginId, "다른 학과 학생", PASSWORD, "sw").andExpect(status().isCreated());
        String applicant = login(loginId);

        Map<String, Long> request = pendingRequest(sw, "sw", loginId);
        json(post("/api/join-requests/{id}/reject", request.get("id")).header(HttpHeaders.IF_MATCH, request.get("version")),
                bearer("sw"), Map.of("reason", "학과 학생 명단에 없습니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, applicant))
                .andExpect(jsonPath("$.joinRequests[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.joinRequests[0].rejectReason").value("학과 학생 명단에 없습니다."));

        // 다시 신청하면 같은 신청이 처리 전으로 돌아가고, 두 번 연달아 신청할 수는 없다.
        json(post("/api/me/join-requests"), applicant, Map.of("groupCode", "sw"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());
        json(post("/api/me/join-requests"), applicant, Map.of("groupCode", "sw"))
                .andExpect(status().isConflict());
        // 다른 그룹에도 신청할 수 있다.
        json(post("/api/me/join-requests"), applicant, Map.of("groupCode", "ai"))
                .andExpect(status().isCreated());
        // 이미 소속인 그룹에는 신청할 수 없다.
        json(post("/api/me/join-requests"), bearer("ce.plan"), Map.of("groupCode", "ce"))
                .andExpect(status().isConflict());
    }

    @Test
    void 서비스_관리자가_가입_코드를_정한다() throws Exception {
        String admin = bearer("admin");
        String code = "grp" + System.nanoTime();
        Map<String, Object> group = new HashMap<>(Map.of("name", "가입코드 테스트 " + code, "joinCode", code.toUpperCase(),
                "type", "DEPARTMENT", "term", "2026"));
        String created = json(post("/api/admin/groups"), admin, group)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joinCode").value(code))
                .andReturn().getResponse().getContentAsString();
        long groupId = ((Number) JsonPath.read(created, "$.id")).longValue();

        // 다른 그룹이 쓰는 코드는 쓸 수 없다(대소문자 무관).
        group.put("name", "가입코드 중복 " + code);
        group.put("joinCode", "ce");
        json(post("/api/admin/groups"), admin, group).andExpect(status().isConflict());
        json(patch("/api/admin/groups/{id}", groupId), admin, Map.of("joinCode", "CE")).andExpect(status().isConflict());
        json(patch("/api/admin/groups/{id}", groupId), admin, Map.of("joinCode", code + "-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinCode").value(code + "-2"));
        // 관리자만 바꾼다.
        json(patch("/api/admin/groups/{id}", groupId), bearer("ce"), Map.of("joinCode", code + "-3"))
                .andExpect(status().isForbidden());

        // 그룹 구성원은 그룹 정보에서 가입 코드를 본다(부원에게 알려 주는 용도).
        mvc.perform(get("/api/orgs/{id}", orgId("컴퓨터공학과 학생회")).header(HttpHeaders.AUTHORIZATION, bearer("ce.member")))
                .andExpect(jsonPath("$.joinCode").value("ce"));
    }
}
