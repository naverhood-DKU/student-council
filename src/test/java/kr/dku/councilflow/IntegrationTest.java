package kr.dku.councilflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 테스트 공통 설정. 모든 테스트 클래스가 같은 스프링 컨텍스트(와 같은 MySQL 컨테이너)를 공유한다.
 * 시드 데이터(총학 → AI 융합대학 → 컴공·소프트웨어)가 들어 있는 상태에서 시작한다.
 * CLOVA Studio 는 테스트 안에서 띄운 가짜 서버(FakeClovaServer)로 대신한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String SEED_PASSWORD = "test-password-1234";
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final FakeClovaServer CLOVA = new FakeClovaServer();

    @DynamicPropertySource
    static void clovaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.clova.studio.base-url", CLOVA::url);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected OrganizationRepository organizationRepository;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 다른 테스트가 남긴 대기 작업을 정리하고 가짜 CLOVA 서버를 비운다. */
    @BeforeEach
    void isolateJobsAndFakeServer() {
        jdbc.update("UPDATE job SET status = 'FAILED', locked_until = NULL WHERE status IN ('QUEUED', 'RUNNING')");
        CLOVA.reset();
    }

    /** 로그인해서 Bearer 헤더 값을 돌려준다. */
    protected String bearer(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, SEED_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    protected long orgId(String name) {
        return organizationRepository.findAll().stream()
                .filter(org -> org.getName().equals(name))
                .map(Organization::getId)
                .findFirst()
                .orElseThrow();
    }

    /** 텍스트 회의록으로 회의를 만들고 회의 id를 돌려준다. */
    protected long createMeeting(String email, long orgId, String transcript) throws Exception {
        String body = mvc.perform(post("/api/meetings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(email))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "orgId", orgId,
                                "title", "정기 회의",
                                "heldAt", Instant.parse("2026-10-08T09:00:00Z").toString(),
                                "transcript", transcript))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.meeting.id")).longValue();
    }

    /** 분석 작업을 만들고 작업 id를 돌려준다. */
    protected long requestAnalysis(String email, long meetingId) throws Exception {
        String body = mvc.perform(post("/api/meetings/{id}/analysis", meetingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(email)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.jobId")).longValue();
    }
}
