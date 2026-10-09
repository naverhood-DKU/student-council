package kr.dku.councilflow.seed;

import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.org.OrgType;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.AppUserRepository;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시연용 가상 데이터. app.seed.enabled=true 일 때만 돈다(local 프로필, 테스트). 운영에서는 꺼져 있다.
 *
 * 총학생회 → AI 융합대학 학생회 → 컴퓨터공학과·소프트웨어학과 학생회.
 * 조직마다 부서 3개(기획부·홍보부·재정부)와 역할별 계정 4개. 로그인하기 쉽게 이메일 대신 짧은 아이디를 쓴다:
 *   {코드}       회장      ORG_ADMIN + MEETING_CHAIR
 *   {코드}.plan  기획부장  DEPT_MEMBER(기획부)
 *   {코드}.pr    홍보부장  DEPT_MEMBER(홍보부)
 *   {코드}.fin   재정부장  FINANCE_REVIEWER(재정부)
 * 코드: union(총학), ai(AI 융합대학), ce(컴공), sw(소프트웨어). 예: 컴공 회장 = ce
 */
@Component
@ConditionalOnBooleanProperty("app.seed.enabled")
class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    SeedDataLoader(OrganizationRepository organizations, DepartmentRepository departments,
                   AppUserRepository users, MembershipRepository memberships, PasswordEncoder passwordEncoder,
                   @Value("${app.seed.password}") String seedPassword) {
        this.organizations = organizations;
        this.departments = departments;
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (organizations.count() > 0) {
            log.info("seed skipped: organizations already exist");
            return;
        }
        String term = Year.now(ZoneId.of("Asia/Seoul")).toString();
        String passwordHash = passwordEncoder.encode(seedPassword);

        Organization union = organizations.save(new Organization("총학생회", OrgType.STUDENT_UNION, null, term));
        Organization college = organizations.save(new Organization("AI 융합대학 학생회", OrgType.COLLEGE, union, term));
        Organization cse = organizations.save(new Organization("컴퓨터공학과 학생회", OrgType.DEPARTMENT, college, term));
        Organization sw = organizations.save(new Organization("소프트웨어학과 학생회", OrgType.DEPARTMENT, college, term));

        seedOrganization(union, "union", "총학", term, passwordHash);
        seedOrganization(college, "ai", "AI융합", term, passwordHash);
        seedOrganization(cse, "ce", "컴공", term, passwordHash);
        seedOrganization(sw, "sw", "소프트웨어", term, passwordHash);
        log.info("seed loaded: 4 organizations, 12 departments, 16 users");
    }

    private void seedOrganization(Organization org, String code, String shortName, String term, String passwordHash) {
        List<Department> saved = departments.saveAll(List.of(
                new Department(org, "기획부", "기획,행사,일정,장소,섭외"),
                new Department(org, "홍보부", "홍보,SNS,공지,포스터,카드뉴스"),
                new Department(org, "재정부", "예산,회계,지출,영수증,정산,학생회비")));
        Department planning = saved.get(0);
        Department pr = saved.get(1);
        Department finance = saved.get(2);

        AppUser chair = user(code, shortName + " 회장", passwordHash);
        memberships.save(new Membership(chair, org, MembershipRole.ORG_ADMIN, null, term));
        memberships.save(new Membership(chair, org, MembershipRole.MEETING_CHAIR, null, term));

        AppUser planner = user(code + ".plan", shortName + " 기획부장", passwordHash);
        memberships.save(new Membership(planner, org, MembershipRole.DEPT_MEMBER, planning, term));

        AppUser prLead = user(code + ".pr", shortName + " 홍보부장", passwordHash);
        memberships.save(new Membership(prLead, org, MembershipRole.DEPT_MEMBER, pr, term));

        AppUser treasurer = user(code + ".fin", shortName + " 재정부장", passwordHash);
        memberships.save(new Membership(treasurer, org, MembershipRole.FINANCE_REVIEWER, finance, term));
    }

    private AppUser user(String loginId, String name, String passwordHash) {
        return users.save(new AppUser(loginId, passwordHash, name));
    }
}
