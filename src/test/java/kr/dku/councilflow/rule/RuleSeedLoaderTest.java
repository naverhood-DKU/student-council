package kr.dku.councilflow.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 학교 규정 시드: 검수한 문서는 모든 그룹이 쓰는 공통 규정으로 확정하고, 아직 검수하지 않은 문서는 DRAFT 로만 넣는다.
 * 테스트 DB 를 다른 테스트와 같이 쓰므로, 끝나면 넣은 공통 규정을 지운다(다른 테스트의 후보 조항에 섞이지 않게).
 */
class RuleSeedLoaderTest extends IntegrationTest {

    @Autowired
    RuleDocumentRepository docs;

    @Autowired
    ClauseRepository clauses;

    @Autowired
    AuditService audit;

    @Autowired
    Clock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void removeGlobalRules() {
        jdbc.update("DELETE FROM clause WHERE doc_id IN (SELECT id FROM rule_document WHERE applies_to_org_id IS NULL)");
        jdbc.update("DELETE FROM rule_document WHERE applies_to_org_id IS NULL");
    }

    private void seed() {
        RuleSeedLoader loader = new RuleSeedLoader(docs, clauses, audit, clock);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                loader.run(null);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void 검수한_규정은_공통_규정으로_확정되고_검수하지_않은_문서는_초안으로만_들어간다() {
        seed();

        assertThat(docs.countByOrgIdIsNullAndStatus(RuleDocStatus.CONFIRMED)).isEqualTo(3);
        assertThat(docs.countByOrgIdIsNullAndStatus(RuleDocStatus.DRAFT)).isEqualTo(2);

        RuleDocument audit2024 = docs.findAll().stream()
                .filter(d -> d.isGlobal() && "단국대학교 감사규정".equals(d.getTitle()))
                .findFirst().orElseThrow();
        assertThat(audit2024.getStatus()).isEqualTo(RuleDocStatus.CONFIRMED);
        assertThat(audit2024.getEffectiveDate()).isEqualTo(LocalDate.of(2024, 8, 21));
        // 검수된 조항만 들어 있고, 원문은 사람이 검수한 값 그대로다.
        List<Clause> all = clauses.findByDocIdOrderBySeq(audit2024.getId());
        assertThat(all).isNotEmpty();
        assertThat(all.stream().filter(Clause::needsReview))
                .allMatch(c -> c.getReviewStatus() == ClauseReviewStatus.REVIEWED);
        assertThat(all.stream().map(Clause::getText).filter(t -> t.contains("학생회 LT")).count()).isGreaterThan(0);

        // 검수하지 않은 문서(2021 본부 감사규정, 사무간담회 안내 자료)는 조항도 검수 전이다.
        for (RuleDocument draft : docs.findAll().stream()
                .filter(d -> d.isGlobal() && d.getStatus() == RuleDocStatus.DRAFT).toList()) {
            assertThat(clauses.findByDocIdOrderBySeq(draft.getId()).stream().filter(Clause::needsReview))
                    .allMatch(c -> c.getReviewStatus() == ClauseReviewStatus.UNREVIEWED);
        }
    }

    @Test
    void 같은_규정을_두_번_넣지_않는다() {
        seed();
        long first = docs.findAll().stream().filter(RuleDocument::isGlobal).count();
        seed();
        assertThat(docs.findAll().stream().filter(RuleDocument::isGlobal).count()).isEqualTo(first);
    }

    @Test
    void 공통_규정의_조항은_어느_그룹에서든_후보로_찾아진다() {
        seed();
        long cse = organizationRepository.findAll().stream()
                .filter(o -> o.getName().equals("컴퓨터공학과 학생회")).findFirst().orElseThrow().getId();

        List<Clause> found = clauses.searchConfirmed(cse, "학생회 LT 숙박비", LocalDate.of(2026, 10, 14), 10);

        assertThat(found).isNotEmpty();
        assertThat(found.stream().map(Clause::getText)).anyMatch(t -> t.contains("학생회 LT"));
    }

    @Test
    void 검수하지_않은_문서의_조항은_후보로_찾아지지_않는다() {
        seed();
        long cse = organizationRepository.findAll().stream()
                .filter(o -> o.getName().equals("컴퓨터공학과 학생회")).findFirst().orElseThrow().getId();

        // 사무간담회 안내 자료(DRAFT)에만 있는 표현
        List<Clause> found = clauses.searchConfirmed(cse, "충전형 결제수단 쿠팡페이 네이버페이", LocalDate.of(2026, 10, 14), 10);

        assertThat(found.stream().map(Clause::getDocId))
                .doesNotContainAnyElementsOf(docs.findAll().stream()
                        .filter(d -> d.isGlobal() && d.getStatus() == RuleDocStatus.DRAFT)
                        .map(RuleDocument::getId).toList());
    }
}
