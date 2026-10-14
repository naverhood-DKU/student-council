package kr.dku.councilflow.rule;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 학교 규정(감사규정·총대의원회 회칙·학생회칙 등)을 서비스가 직접 넣는다.
 *
 * 규정은 그룹 회장이 올려 검수하는 것이 아니라 개발 시점에 한 번 검수한 값을 resources/rules/*.json 으로 두고,
 * 앱이 뜰 때 모든 그룹이 쓰는 공통 규정(applies_to_org_id = NULL)으로 넣는다. 규정이 개정되면 json 을 바꿔 새로 배포한다.
 *
 * - verified = true  : 사람이 원문과 대조해 검수한 문서. 조항을 모두 검수 완료로 하고 CONFIRMED 로 넣는다.
 * - verified = false : 아직 검수하지 않은 문서. DRAFT 로만 넣어 지출 검색에는 쓰이지 않는다(원칙 4).
 *   검수한 뒤 json 의 verified 를 true 로 바꾸면 다음 기동에서 확정한다.
 * - 같은 원본(sourceSha256)은 두 번 넣지 않는다. 같은 제목의 옛 공통 규정이 확정돼 있으면 새 규정을 확정할 때 폐기한다.
 *
 * worker 프로필은 건너뛴다(App 과 동시에 뜰 때 중복으로 넣지 않도록).
 */
@Component
@Profile("!worker")
@ConditionalOnBooleanProperty(name = "app.rules.seed.enabled", matchIfMissing = true)
class RuleSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuleSeedLoader.class);
    private static final String PATTERN = "classpath*:rules/*.json";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** json 한 파일의 모양 */
    record SeedDocument(String key, boolean verified, String title, String issuer, String appliesTo,
                        String effectiveDate, String docVersion, String notes, String fileName,
                        String sourceSha256, boolean roundtripOk, String sourceText, List<SeedClause> clauses) {
    }

    record SeedClause(int seq, String kind, String label, Integer articleNo, Integer branchNumber, String title,
                      String chapter, String sectionName, String text, boolean edited, String flags) {
    }

    private final RuleDocumentRepository docs;
    private final ClauseRepository clauses;
    private final AuditService audit;
    private final Clock clock;

    RuleSeedLoader(RuleDocumentRepository docs, ClauseRepository clauses, AuditService audit, Clock clock) {
        this.docs = docs;
        this.clauses = clauses;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        int added = 0;
        for (SeedDocument seed : load()) {
            if (seed(seed)) {
                added++;
            }
        }
        if (added > 0) {
            log.info("seeded {} global rule document(s)", added);
        }
    }

    /** resources/rules 의 json 을 모두 읽는다. 이름순이라 항상 같은 순서로 넣는다. */
    static List<SeedDocument> load() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        Arrays.sort(resources, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
        List<SeedDocument> result = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                result.add(MAPPER.readValue(in, SeedDocument.class));
            }
        }
        return result;
    }

    /** 넣었거나(확정 포함) 상태를 바꿨으면 true */
    private boolean seed(SeedDocument seed) {
        var existing = docs.findFirstByOrgIdIsNullAndSourceSha256(seed.sourceSha256());
        if (existing.isPresent()) {
            RuleDocument doc = existing.get();
            if (seed.verified() && doc.getStatus() == RuleDocStatus.DRAFT) {
                // 검수를 마쳐 verified 로 바꾼 문서: 조항을 검수 완료로 하고 확정한다.
                clauses.findByDocIdOrderBySeq(doc.getId()).forEach(Clause::markReviewed);
                confirm(doc);
                return true;
            }
            return false;
        }

        RuleDocument doc = docs.save(new RuleDocument(null, seed.title(), RuleSourceType.REGULATION, seed.issuer(),
                seed.appliesTo(), LocalDate.parse(seed.effectiveDate()), seed.docVersion(), seed.notes(),
                seed.fileName(), seed.sourceSha256(), seed.sourceText(), seed.roundtripOk()));
        List<Clause> created = new ArrayList<>();
        for (SeedClause c : seed.clauses()) {
            Clause clause = new Clause(doc.getId(), c.seq(), RuleTextSplitter.Kind.valueOf(c.kind()), c.label(),
                    c.articleNo(), c.branchNumber(), c.title(), c.chapter(), c.sectionName(), c.text(),
                    c.flags() == null || c.flags().isBlank() ? List.of() : Arrays.asList(c.flags().split("\n")));
            if (c.edited()) {
                clause.markEdited();
            }
            if (seed.verified()) {
                clause.markReviewed();
            }
            created.add(clause);
        }
        clauses.saveAll(created);

        if (seed.verified()) {
            confirm(doc);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("key", seed.key());
        after.put("title", seed.title());
        after.put("clauses", created.size());
        after.put("status", doc.getStatus().name());
        audit.record(null, null, "RULE_DOC_SEEDED", "RULE_DOCUMENT", doc.getId(), null, after);
        return true;
    }

    /** 확정한다. 같은 제목으로 확정돼 있던 옛 공통 규정은 폐기해, 같은 규정이 두 버전으로 찾아지지 않게 한다. */
    private void confirm(RuleDocument doc) {
        for (RuleDocument old : docs.findByOrgIdIsNullAndTitleAndStatus(doc.getTitle(), RuleDocStatus.CONFIRMED)) {
            if (!old.getId().equals(doc.getId())) {
                old.retire();
            }
        }
        doc.confirmBySystem(clock.instant());
    }
}
