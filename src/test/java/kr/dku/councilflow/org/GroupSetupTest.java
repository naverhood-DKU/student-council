package kr.dku.councilflow.org;

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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 그룹 구성 설정: 회장이 부서와 직책을 만들고 고친다.
 * 직책마다 권한 수준(부장급·부원급)이 있고, 구성원의 권한은 직책을 따른다.
 */
class GroupSetupTest extends IntegrationTest {

    private static final String PASSWORD = "setup-password-1234";

    private ResultActions json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    @Test
    void 회장이_부서를_만들고_고치고_없앤다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String owner = bearer("sw");
        String name = "디자인부" + System.nanoTime() % 100000;

        // 부장은 부서를 만들 수 없다.
        json(post("/api/orgs/{id}/departments", sw), bearer("sw.plan"), Map.of("name", name))
                .andExpect(status().isForbidden());
        String created = body(json(post("/api/orgs/{id}/departments", sw), owner,
                Map.of("name", name, "roleKeywords", " 포스터, 디자인 ,,굿즈 "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleKeywords").value("포스터,디자인,굿즈")));
        long departmentId = num(created, "$.id");
        json(post("/api/orgs/{id}/departments", sw), owner, Map.of("name", name))
                .andExpect(status().isConflict());

        // 구성원 누구나 목록을 보고, 그룹 정보의 부서 목록에도 나온다.
        mvc.perform(get("/api/orgs/{id}/departments", sw).header(HttpHeaders.AUTHORIZATION, bearer("sw.member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + name + "')].memberCount").value(0));

        // 이름 바꾸기(If-Match), 다른 부서 이름과 겹치면 409
        json(patch("/api/departments/{id}", departmentId).header(HttpHeaders.IF_MATCH, num(created, "$.version")), owner,
                Map.of("name", "기획부"))
                .andExpect(status().isConflict());
        String renamed = body(json(patch("/api/departments/{id}", departmentId)
                        .header(HttpHeaders.IF_MATCH, num(created, "$.version")), owner, Map.of("name", name + "팀"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name + "팀")));
        assertThat(num(renamed, "$.version")).isGreaterThan(num(created, "$.version"));

        // 구성원이 있는 부서는 없앨 수 없다.
        long planning = departmentIdByName(sw, owner, "기획부");
        mvc.perform(delete("/api/departments/{id}", planning).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isConflict());

        // 빈 부서는 없앤다(숨김). 그룹 부서 목록에서 빠지고, 거기에 사람을 넣을 수 없다.
        mvc.perform(delete("/api/departments/{id}", departmentId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNoContent());
        String org = body(mvc.perform(get("/api/orgs/{id}", sw).header(HttpHeaders.AUTHORIZATION, owner)));
        List<Object> ids = JsonPath.read(org, "$.departments[*].id");
        assertThat(ids).doesNotContain((int) departmentId);

        // 같은 이름으로 다시 만들면 그 부서를 되살린다.
        json(post("/api/orgs/{id}/departments", sw), owner, Map.of("name", name + "팀"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(departmentId));
    }

    @Test
    void 직책을_만들어_가입을_승인하면_직책의_권한을_따른다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String owner = bearer("sw");
        String positionName = "총무" + System.nanoTime() % 100000;

        // 기본 직책은 부장(부장급)·부원(부원급)
        mvc.perform(get("/api/orgs/{id}/positions", sw).header(HttpHeaders.AUTHORIZATION, bearer("sw.member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("부장"))
                .andExpect(jsonPath("$[0].level").value("EDITOR"))
                .andExpect(jsonPath("$[1].name").value("부원"))
                .andExpect(jsonPath("$[1].level").value("VIEWER"));
        // 부원은 직책을 만들 수 없고, 회장급 직책은 만들 수 없다.
        json(post("/api/orgs/{id}/positions", sw), bearer("sw.member"), Map.of("name", positionName, "level", "EDITOR"))
                .andExpect(status().isForbidden());
        json(post("/api/orgs/{id}/positions", sw), owner, Map.of("name", positionName, "level", "OWNER"))
                .andExpect(status().isBadRequest());
        String created = body(json(post("/api/orgs/{id}/positions", sw), owner,
                Map.of("name", positionName, "level", "EDITOR"))
                .andExpect(status().isCreated()));
        long positionId = num(created, "$.id");

        // 새 사람이 가입하고, 회장이 '총무' 직책 + 재정부로 승인한다.
        String loginId = "setup." + System.nanoTime();
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("loginId", loginId, "name", "새 총무",
                                "password", PASSWORD, "groupCode", "sw"))))
                .andExpect(status().isCreated());
        String requests = body(mvc.perform(get("/api/orgs/{id}/join-requests", sw).header(HttpHeaders.AUTHORIZATION, owner)));
        List<Map<String, Object>> mine = JsonPath.read(requests, "$[?(@.loginId == '" + loginId + "')]");
        long requestId = ((Number) mine.get(0).get("id")).longValue();
        long requestVersion = ((Number) mine.get(0).get("version")).longValue();
        long finance = departmentIdByName(sw, owner, "재정부");
        json(post("/api/join-requests/{id}/approve", requestId).header(HttpHeaders.IF_MATCH, requestVersion), owner,
                Map.of("positionId", positionId, "departmentId", finance))
                .andExpect(status().isOk());

        String newcomer = login(loginId);
        mvc.perform(get("/api/orgs/{id}", sw).header(HttpHeaders.AUTHORIZATION, newcomer))
                .andExpect(jsonPath("$.myRole").value("EDITOR"))
                .andExpect(jsonPath("$.myPositionName").value(positionName));
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, newcomer))
                .andExpect(jsonPath("$.memberships[0].positionName").value(positionName));

        // 직책을 부원급으로 바꾸면 그 직책을 가진 사람의 권한도 부원급이 된다.
        json(patch("/api/positions/{id}", positionId).header(HttpHeaders.IF_MATCH, num(created, "$.version")), owner,
                Map.of("level", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(1));
        mvc.perform(get("/api/orgs/{id}", sw).header(HttpHeaders.AUTHORIZATION, newcomer))
                .andExpect(jsonPath("$.myRole").value("VIEWER"));

        // 사람이 있는 직책은 지울 수 없다. 빈 직책은 지운다.
        mvc.perform(delete("/api/positions/{id}", positionId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isConflict());
        String spare = body(json(post("/api/orgs/{id}/positions", sw), owner,
                Map.of("name", "임시" + System.nanoTime() % 100000, "level", "VIEWER"))
                .andExpect(status().isCreated()));
        mvc.perform(delete("/api/positions/{id}", num(spare, "$.id")).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNoContent());
    }

    @Test
    void 부서_없는_사람이_있으면_직책을_부장급으로_올릴_수_없다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String owner = bearer("sw");
        String created = body(json(post("/api/orgs/{id}/positions", sw), owner,
                Map.of("name", "서기" + System.nanoTime() % 100000, "level", "VIEWER"))
                .andExpect(status().isCreated()));
        long positionId = num(created, "$.id");

        // 부서 없이 서기로 직접 초대
        json(post("/api/orgs/{id}/members", sw), owner, Map.of("loginId", "clerk." + System.nanoTime(), "name", "서기",
                "initialPassword", PASSWORD, "positionId", positionId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.positionId").value(positionId))
                .andExpect(jsonPath("$.departmentId").doesNotExist());

        json(patch("/api/positions/{id}", positionId).header(HttpHeaders.IF_MATCH, num(created, "$.version")), owner,
                Map.of("level", "EDITOR"))
                .andExpect(status().isConflict());
    }

    private long departmentIdByName(long orgId, String token, String name) throws Exception {
        String org = body(mvc.perform(get("/api/orgs/{id}", orgId).header(HttpHeaders.AUTHORIZATION, token)));
        List<Number> ids = JsonPath.read(org, "$.departments[?(@.name == '" + name + "')].id");
        return ids.get(0).longValue();
    }

    private String login(String loginId) throws Exception {
        String response = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email", loginId, "password", PASSWORD))))
                .andExpect(status().isOk()));
        return "Bearer " + JsonPath.read(response, "$.accessToken");
    }
}
