package kr.dku.councilflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V5(역할 개편)·V7(직책)이 예전 역할 데이터가 들어 있는 DB에서도 돌아가는지 본다.
 * 다른 테스트는 빈 DB에 V1~V5를 한 번에 올리므로 이 경우를 잡지 못한다.
 * 같은 MySQL 컨테이너 안에 따로 DB를 만들어 V4까지 올리고, 예전 데이터를 넣은 뒤 V5를 올린다.
 */
class GroupRolesMigrationTest extends IntegrationTest {

    private static final String DB = "v5_migration_check";

    @Autowired
    MySQLContainer mysql;

    @Test
    void 한_사람이_예전_역할을_여러_개_가져도_그룹마다_역할_하나로_합친다() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(""), "root", mysql.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB);
            statement.execute("CREATE DATABASE " + DB);
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(rootUrl(DB), "root", mysql.getPassword());
        migrateTo(dataSource, "4");

        JdbcTemplate legacy = new JdbcTemplate(dataSource);
        legacy.update("INSERT INTO organization (id, name, type, term, created_at, updated_at) "
                + "VALUES (1, '테스트 학생회', 'DEPARTMENT', '2026', NOW(6), NOW(6))");
        for (int user = 1; user <= 3; user++) {
            legacy.update("INSERT INTO app_user (id, email, password_hash, name, created_at, updated_at) "
                    + "VALUES (?, ?, 'x', ?, NOW(6), NOW(6))", user, "user" + user, "사용자" + user);
        }
        // 회장: 조직 관리자 + 회의 책임자 → OWNER 하나
        insertMembership(legacy, 1, "ORG_ADMIN", true);
        insertMembership(legacy, 1, "MEETING_CHAIR", true);
        // 재정부장: 부서원 + 재정 담당 → EDITOR 하나
        insertMembership(legacy, 2, "DEPT_MEMBER", true);
        insertMembership(legacy, 2, "FINANCE_REVIEWER", true);
        // 끊긴 회의 책임자 소속보다 활성 부서원 소속이 남는다
        insertMembership(legacy, 3, "MEETING_CHAIR", false);
        insertMembership(legacy, 3, "DEPT_MEMBER", true);

        migrateTo(dataSource, "5");

        List<Map<String, Object>> rows = legacy.queryForList(
                "SELECT user_id, role, active FROM membership ORDER BY user_id");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get("role")).containsExactly("OWNER", "EDITOR", "EDITOR");
        assertThat(rows).extracting(row -> row.get("active")).containsExactly(true, true, true);
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM app_user WHERE service_admin", Integer.class)).isZero();

        // V7: 그룹마다 기본 직책(부장·부원)이 생기고, 부장·부원 소속에 같은 권한 수준의 직책이 붙는다.
        migrateTo(dataSource, "7");
        List<Map<String, Object>> withPositions = legacy.queryForList(
                "SELECT m.role, p.name AS position_name, p.level FROM membership m"
                        + " LEFT JOIN org_position p ON p.id = m.position_id ORDER BY m.user_id");
        assertThat(withPositions).extracting(row -> row.get("position_name")).containsExactly(null, "부장", "부장");
        assertThat(withPositions).filteredOn(row -> row.get("level") != null)
                .allSatisfy(row -> assertThat(row.get("level")).isEqualTo(row.get("role")));

        legacy.execute("DROP DATABASE " + DB);
    }

    private String rootUrl(String database) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database;
    }

    private static void migrateTo(DriverManagerDataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static void insertMembership(JdbcTemplate jdbc, long userId, String role, boolean active) {
        jdbc.update("INSERT INTO membership (user_id, org_id, role, active, term, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, '2026', NOW(6), NOW(6))", userId, role, active);
    }
}
