package kr.dku.councilflow.seed;

import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import kr.dku.councilflow.org.DefaultDepartments;
import kr.dku.councilflow.org.DefaultPositions;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.org.OrgType;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.org.Position;
import kr.dku.councilflow.org.PositionRepository;
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.AppUserRepository;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
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
 * 그룹마다 부서 3개(기획부·홍보부·재정부)와 계정 5개. 로그인하기 쉽게 이메일 대신 짧은 아이디를 쓴다:
 *   {코드}         회장      OWNER
 *   {코드}.plan    기획부장  EDITOR(기획부)
 *   {코드}.pr      홍보부장  EDITOR(홍보부)
 *   {코드}.fin     재정부장  EDITOR(재정부)
 *   {코드}.member  부원      VIEWER(기획부)
 * 서비스 관리자: admin (그룹 소속 없음)
 * 코드: union(총학), ai(AI 융합대학), ce(컴공), sw(소프트웨어). 예: 컴공 회장 = ce
 */
@Component
@ConditionalOnBooleanProperty("app.seed.enabled")
class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final PositionRepository positions;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    SeedDataLoader(OrganizationRepository organizations, DepartmentRepository departments, PositionRepository positions,
                   AppUserRepository users, MembershipRepository memberships, PasswordEncoder passwordEncoder,
                   @Value("${app.seed.password}") String seedPassword) {
        this.organizations = organizations;
        this.departments = departments;
        this.positions = positions;
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
        user("admin", "서비스 관리자", passwordHash).grantServiceAdmin();
        log.info("seed loaded: 4 groups, 12 departments, 21 users");
    }

    private void seedOrganization(Organization org, String code, String shortName, String term, String passwordHash) {
        org.changeJoinCode(code);
        List<Department> saved = departments.saveAll(DefaultDepartments.of(org));
        Department planning = saved.get(0);
        Department pr = saved.get(1);
        Department finance = saved.get(2);

        List<Position> savedPositions = positions.saveAll(DefaultPositions.of(org));
        Position lead = savedPositions.get(0);
        Position crew = savedPositions.get(1);

        AppUser owner = user(code, shortName + " 회장", passwordHash);
        memberships.save(Membership.owner(owner, org, term));

        AppUser planner = user(code + ".plan", shortName + " 기획부장", passwordHash);
        memberships.save(Membership.withPosition(planner, org, lead, planning, term));

        AppUser prLead = user(code + ".pr", shortName + " 홍보부장", passwordHash);
        memberships.save(Membership.withPosition(prLead, org, lead, pr, term));

        AppUser treasurer = user(code + ".fin", shortName + " 재정부장", passwordHash);
        memberships.save(Membership.withPosition(treasurer, org, lead, finance, term));

        AppUser member = user(code + ".member", shortName + " 부원", passwordHash);
        memberships.save(Membership.withPosition(member, org, crew, planning, term));
    }

    private AppUser user(String loginId, String name, String passwordHash) {
        return users.save(new AppUser(loginId, passwordHash, name));
    }
}
