package kr.dku.councilflow.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.AppUserRepository;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 원칙 8: 조직 데이터는 그 조직의 활성 소속만 볼 수 있다. 상위 조직이라고 하위 조직을 볼 수 없다. */
class OrgAccessTest extends IntegrationTest {

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    MembershipRepository membershipRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void 자기_조직은_조회할_수_있다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");

        mvc.perform(get("/api/orgs/{id}", cse)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cse))
                .andExpect(jsonPath("$.name").value("컴퓨터공학과 학생회"))
                .andExpect(jsonPath("$.departments.length()").value(3))
                .andExpect(jsonPath("$.myRoles").isArray());
    }

    @Test
    void 다른_학과_조직을_조회하면_403() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");

        mvc.perform(get("/api/orgs/{id}", sw)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void 상위_조직_소속도_하위_조직을_직접_조회하면_403() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");

        mvc.perform(get("/api/orgs/{id}", cse)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ai")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/api/orgs/{id}", cse)
                        .header(HttpHeaders.AUTHORIZATION, bearer("union")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 비활성_소속으로는_조회할_수_없다() throws Exception {
        Organization sw = organizationRepository.findById(orgId("소프트웨어학과 학생회")).orElseThrow();
        AppUser former = userRepository.save(
                new AppUser("former.sw@councilflow.test", passwordEncoder.encode(SEED_PASSWORD), "지난 임기 회장"));
        Membership membership = new Membership(former, sw, MembershipRole.MEETING_CHAIR, null, "2025");
        membership.deactivate();
        membershipRepository.save(membership);

        mvc.perform(get("/api/orgs/{id}", sw.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer("former.sw@councilflow.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 존재하지_않는_조직도_403으로_답한다() throws Exception {
        // 조직이 있는지 없는지 외부에 알려 주지 않는다.
        mvc.perform(get("/api/orgs/{id}", 999_999)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(get("/api/orgs/{id}", orgId("컴퓨터공학과 학생회")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void 위조된_토큰이면_401() throws Exception {
        mvc.perform(get("/api/orgs/{id}", orgId("컴퓨터공학과 학생회"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
