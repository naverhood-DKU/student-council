package kr.dku.councilflow.auth;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.dku.councilflow.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AuthTest extends IntegrationTest {

    @Test
    void 시드_계정으로_로그인하면_15분짜리_토큰을_받는다() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ce", "password": "%s"}
                                """.formatted(SEED_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void 비밀번호가_틀리면_401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ce", "password": "wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 없는_계정도_같은_401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@councilflow.test", "password": "whatever-1234"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 이메일이_비어_있으면_400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "", "password": "whatever-1234"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void 내_정보에는_활성_소속만_나온다() throws Exception {
        mvc.perform(get("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ce"))
                .andExpect(jsonPath("$.memberships[*].orgName", hasItem("컴퓨터공학과 학생회")))
                .andExpect(jsonPath("$.memberships[*].role", hasItem("MEETING_CHAIR")))
                .andExpect(jsonPath("$.memberships[*].role", hasItem("ORG_ADMIN")));
    }

    @Test
    void 헬스체크는_인증_없이_열려_있다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void 헬스체크_외_actuator는_인증이_필요하다() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 모든_응답에_요청_ID가_붙고_들어온_ID는_그대로_쓴다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));

        mvc.perform(get("/actuator/health").header("X-Request-Id", "lb-abc-123"))
                .andExpect(header().string("X-Request-Id", "lb-abc-123"));
    }

    @Test
    void 이상한_요청_ID는_버리고_새로_만든다() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "bad id\nwith newline"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }
}
