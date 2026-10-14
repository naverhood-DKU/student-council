package kr.dku.councilflow.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 규정 등록·조항 분리·검수·확정.
 *
 * 흐름: 회장이 파일(또는 글자)과 발행 주체·시행일·버전을 넣어 올린다 → 서버가 "제n조" 기준으로 나눠 DRAFT 로 저장
 * → 회장이 조항을 하나씩 확인(필요하면 고침·추가·삭제)하고 검수 완료 표시 → 모든 조·보칙·부칙을 검수하면 확정.
 * 확정한 문서만 지출 검토의 근거가 된다. 분리는 자동이지만 확정은 사람만 한다(CLAUDE.md 원칙 1·4).
 *
 * 보기는 그 그룹 구성원(서비스 관리자는 읽기 전용), 바꾸기는 그 그룹 회장만. 다른 그룹의 규정은 볼 수 없다.
 */
@Service
public class RuleService {

    static final int MAX_SOURCE_CHARS = 2_000_000;
    static final int MAX_PIECES = 2000;
    static final int MAX_CLAUSE_CHARS = 100_000;
    private static final Set<RuleTextSplitter.Kind> REVIEW_KINDS = EnumSet.of(
            RuleTextSplitter.Kind.ARTICLE, RuleTextSplitter.Kind.EXTRA, RuleTextSplitter.Kind.SUPPLEMENTARY);

    private final RuleDocumentRepository docs;
    private final ClauseRepository clauses;
    private final OrgAccessGuard accessGuard;
    private final AuditService audit;
    private final Clock clock;

    RuleService(RuleDocumentRepository docs, ClauseRepository clauses, OrgAccessGuard accessGuard,
                AuditService audit, Clock clock) {
        this.docs = docs;
        this.clauses = clauses;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- 화면에 내보내는 모양 ----

    public record DocSummary(long id, String title, RuleSourceType sourceType, String issuer,
                             LocalDate effectiveDate, String docVersion, RuleDocStatus status,
                             long articleCount, long unreviewedCount, boolean roundtripOk, long version) {
    }

    public record ClauseView(long id, int seq, RuleTextSplitter.Kind kind, String label, Integer number,
                             Integer branchNumber, String title, String chapter, String section, String text,
                             ClauseReviewStatus reviewStatus, boolean edited, List<String> flags, long version) {
        static ClauseView of(Clause c) {
            return new ClauseView(c.getId(), c.getSeq(), c.getKind(), c.getLabel(), c.getArticleNo(), c.getBranchNumber(),
                    c.getTitle(), c.getChapter(), c.getSectionName(), c.getText(), c.getReviewStatus(), c.isEdited(),
                    c.flagList(), c.getVersion());
        }
    }

    public record DocDetail(DocSummary summary, String appliesTo, String notes, String fileName, String sourceSha256,
                            Long reviewedBy, Instant reviewedAt, List<String> registrationWarnings,
                            List<ClauseView> clauses) {
    }

    /** 등록 요청. 파일과 글자 중 하나만 넣는다. bodyStartLine 은 목차를 건너뛰는 기준 줄(없으면 제목). */
    public record RegisterRequest(String title, String issuer, String appliesTo, LocalDate effectiveDate,
                                  String docVersion, String notes, String bodyStartLine,
                                  byte[] fileBytes, String fileName, String text) {
    }

    // ---- 등록 ----

    @Transactional
    public DocDetail register(CurrentUser user, long orgId, RegisterRequest request) {
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        String title = required(request.title(), "규정 이름", 200);
        String issuer = required(request.issuer(), "발행 주체", 200);
        String appliesTo = optional(request.appliesTo(), "적용 대상", 300);
        String docVersion = optional(request.docVersion(), "규정 버전", 100);

        boolean hasFile = request.fileBytes() != null && request.fileBytes().length > 0;
        boolean hasText = request.text() != null && !request.text().isBlank();
        if (hasFile == hasText) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "파일을 올리거나 글자를 붙여 넣어 주세요(둘 중 하나만).");
        }
        byte[] sourceBytes = hasFile ? request.fileBytes() : request.text().getBytes(StandardCharsets.UTF_8);
        String raw = hasFile ? RuleTextExtractor.extract(request.fileBytes(), request.fileName()) : request.text();
        if (raw.length() > MAX_SOURCE_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "규정이 너무 깁니다.");
        }

        String startLine = request.bodyStartLine() == null || request.bodyStartLine().isBlank()
                ? title : request.bodyStartLine();
        RuleTextSplitter.Result split = RuleTextSplitter.split(raw, startLine);
        long articleCount = split.pieces().stream().filter(p -> p.kind() == RuleTextSplitter.Kind.ARTICLE).count();
        if (articleCount == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'제n조(제목)' 모양의 조항을 찾지 못했습니다. 회칙·세칙·규정 파일이 맞는지 확인해 주세요.");
        }
        if (split.pieces().size() > MAX_PIECES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조항이 너무 많습니다(" + split.pieces().size() + "개).");
        }

        RuleDocument doc = docs.save(new RuleDocument(orgId, title, RuleSourceType.REGULATION, issuer, appliesTo,
                request.effectiveDate(), docVersion, optional(request.notes(), "메모", 5000),
                hasFile ? trimName(request.fileName()) : null, sha256(sourceBytes), split.body(),
                split.roundtripOk()));
        List<Clause> rows = new ArrayList<>();
        int seq = 1;
        for (RuleTextSplitter.Piece p : split.pieces()) {
            rows.add(new Clause(doc.getId(), seq++, p.kind(), p.label(), p.number(), p.branch(), p.title(),
                    p.chapter(), p.section(), p.text(), p.flags()));
        }
        clauses.saveAll(rows);

        List<String> warnings = new ArrayList<>();
        if (!split.roundtripOk()) {
            warnings.add("나눈 조항을 이어 붙인 결과가 원문과 달랐습니다. 분리 결과를 원문과 대조해 주세요.");
        }
        if (!split.bodyStartFound()) {
            warnings.add("본문 시작 기준 줄('" + startLine + "')을 찾지 못해 처음부터 나눴습니다. 목차가 조항으로 섞였을 수 있습니다.");
        }
        audit.record(access.userId(), orgId, "RULE_DOC_REGISTERED", "RULE_DOCUMENT", doc.getId(), null,
                registrationSnapshot(doc, rows.size(), articleCount));
        return detail(doc, warnings);
    }

    // ---- 조회 ----

    @Transactional(readOnly = true)
    public List<DocSummary> list(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return docs.findByOrgIdOrderByIdDesc(orgId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public DocDetail get(CurrentUser user, long docId) {
        RuleDocument doc = load(docId);
        if (!doc.isGlobal()) {
            accessGuard.requireMember(user, doc.getOrgId());
        }
        // 공통 규정은 로그인한 누구나 읽을 수 있다(원문은 학교 규정이다).
        return detail(doc, List.of());
    }

    // ---- 수정 ----

    /** 문서 정보 수정(DRAFT 만). 보낸 필드만 바꾼다. 시행일을 비우려면 null 이나 빈 글자를 보낸다. */
    @Transactional
    public DocSummary updateMetadata(CurrentUser user, long docId, String ifMatch, JsonNode body) {
        RuleDocument doc = lockedDoc(docId);
        OrgAccess access = requireOwnerOf(user, doc);
        IfMatch.check(ifMatch, doc.getVersion());
        doc.requireDraft();
        Map<String, Object> before = metadataSnapshot(doc);
        String title = doc.getTitle();
        String issuer = doc.getIssuer();
        String appliesTo = doc.getAppliesTo();
        LocalDate effectiveDate = doc.getEffectiveDate();
        String docVersion = doc.getDocVersion();
        String notes = doc.getNotes();
        if (body != null && body.has("title")) {
            title = required(text(body.get("title")), "규정 이름", 200);
        }
        if (body != null && body.has("issuer")) {
            issuer = required(text(body.get("issuer")), "발행 주체", 200);
        }
        if (body != null && body.has("appliesTo")) {
            appliesTo = optional(text(body.get("appliesTo")), "적용 대상", 300);
        }
        if (body != null && body.has("effectiveDate")) {
            effectiveDate = date(text(body.get("effectiveDate")));
        }
        if (body != null && body.has("docVersion")) {
            docVersion = optional(text(body.get("docVersion")), "규정 버전", 100);
        }
        if (body != null && body.has("notes")) {
            notes = optional(text(body.get("notes")), "메모", 5000);
        }
        doc.changeMetadata(title, doc.getSourceType(), issuer, appliesTo, effectiveDate, docVersion, notes);
        docs.flush();
        audit.record(access.userId(), doc.getOrgId(), "RULE_DOC_UPDATED", "RULE_DOCUMENT", docId, before,
                metadataSnapshot(doc));
        return summary(doc);
    }

    /** 조항 수정 { label?, title?, text?, reviewed? } (If-Match: 조항 version). 글을 고치면 검수 표시는 풀린다. */
    @Transactional
    public ClauseView updateClause(CurrentUser user, long clauseId, String ifMatch, JsonNode body) {
        Clause clause = loadClause(clauseId);
        RuleDocument doc = lockedDoc(clause.getDocId());
        OrgAccess access = requireOwnerOf(user, doc);
        doc.requireDraft();
        IfMatch.check(ifMatch, clause.getVersion());
        Map<String, Object> before = clauseSnapshot(clause);
        String label = clause.getLabel();
        String title = clause.getTitle();
        String textValue = clause.getText();
        if (body != null && body.has("label")) {
            label = required(text(body.get("label")), "조항 번호", 60);
        }
        if (body != null && body.has("title")) {
            title = optional(text(body.get("title")), "조항 제목", 300);
        }
        if (body != null && body.has("text")) {
            textValue = clauseText(text(body.get("text")));
        }
        clause.edit(label, title, textValue);
        if (body != null && body.has("reviewed")) {
            if (body.get("reviewed").asBoolean(false)) {
                clause.markReviewed();
            } else {
                clause.markUnreviewed();
            }
        }
        clauses.flush();
        audit.record(access.userId(), doc.getOrgId(), "CLAUSE_UPDATED", "CLAUSE", clauseId, before,
                clauseSnapshot(clause));
        return ClauseView.of(clause);
    }

    /** 분리가 놓친 조항을 직접 끼워 넣는다 { afterClauseId?, kind?, label, title?, text }. 검수 전 상태로 들어간다. */
    @Transactional
    public ClauseView addClause(CurrentUser user, long docId, JsonNode body) {
        RuleDocument doc = lockedDoc(docId);
        OrgAccess access = requireOwnerOf(user, doc);
        doc.requireDraft();
        if (clauses.countByDocId(docId) >= MAX_PIECES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조항이 너무 많습니다.");
        }
        RuleTextSplitter.Kind kind = RuleTextSplitter.Kind.ARTICLE;
        if (body != null && body.has("kind") && !body.get("kind").isNull()) {
            try {
                kind = RuleTextSplitter.Kind.valueOf(body.get("kind").asString());
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "종류가 올바르지 않습니다.");
            }
            if (!REVIEW_KINDS.contains(kind)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "조·보칙·부칙만 직접 추가할 수 있습니다.");
            }
        }
        String label = required(body == null ? null : text(body.get("label")), "조항 번호", 60);
        String title = optional(body == null ? null : text(body.get("title")), "조항 제목", 300);
        String textValue = clauseText(body == null ? null : text(body.get("text")));

        List<Clause> existing = clauses.findByDocIdOrderBySeq(docId);
        int insertSeq;
        String chapter = null;
        String section = null;
        Long afterId = body != null && body.has("afterClauseId") && !body.get("afterClauseId").isNull()
                ? body.get("afterClauseId").asLong() : null;
        if (afterId == null) {
            insertSeq = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSeq() + 1;
        } else {
            Clause after = existing.stream().filter(c -> c.getId().equals(afterId)).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "끼워 넣을 위치의 조항을 찾지 못했습니다."));
            insertSeq = after.getSeq() + 1;
            chapter = after.getChapter();
            section = after.getSectionName();
            clauses.shiftFrom(docId, insertSeq);
        }
        Clause created = new Clause(docId, insertSeq, kind, label, null, null, title, chapter, section, textValue,
                List.of("사람이 직접 추가한 조항"));
        created.markEdited();
        clauses.save(created);
        audit.record(access.userId(), doc.getOrgId(), "CLAUSE_ADDED", "CLAUSE", created.getId(), null,
                clauseSnapshot(created));
        return ClauseView.of(created);
    }

    @Transactional
    public void deleteClause(CurrentUser user, long clauseId) {
        Clause clause = loadClause(clauseId);
        RuleDocument doc = lockedDoc(clause.getDocId());
        OrgAccess access = requireOwnerOf(user, doc);
        doc.requireDraft();
        Map<String, Object> before = clauseSnapshot(clause);
        clauses.delete(clause);
        audit.record(access.userId(), doc.getOrgId(), "CLAUSE_DELETED", "CLAUSE", clauseId, before, null);
    }

    /** 여러 조항을 한꺼번에 "검수 완료"로. 사람이 화면에서 고른 조항만 바꾼다. */
    @Transactional
    public int reviewClauses(CurrentUser user, long docId, List<Long> clauseIds) {
        RuleDocument doc = lockedDoc(docId);
        OrgAccess access = requireOwnerOf(user, doc);
        doc.requireDraft();
        if (clauseIds == null || clauseIds.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "검수 완료할 조항을 골라 주세요.");
        }
        List<Clause> targets = clauses.findByDocIdAndIdIn(docId, clauseIds);
        if (targets.size() != clauseIds.stream().distinct().count()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "이 규정에 없는 조항이 섞여 있습니다.");
        }
        targets.forEach(Clause::markReviewed);
        clauses.flush();
        audit.record(access.userId(), doc.getOrgId(), "CLAUSES_REVIEWED", "RULE_DOCUMENT", docId, null,
                Map.of("clauseIds", targets.stream().map(Clause::getId).sorted().toList()));
        return targets.size();
    }

    // ---- 확정·폐기·삭제 ----

    /**
     * 확정. 발행 주체·시행일·버전이 있고, 모든 조·보칙·부칙이 검수 완료일 때만 된다.
     * 확정한 사람과 시각을 남긴다.
     */
    @Transactional
    public DocSummary confirm(CurrentUser user, long docId, String ifMatch) {
        RuleDocument doc = lockedDoc(docId);
        OrgAccess access = requireOwnerOf(user, doc);
        IfMatch.check(ifMatch, doc.getVersion());
        doc.requireDraft();
        List<String> missing = new ArrayList<>();
        if (doc.getEffectiveDate() == null) {
            missing.add("시행일");
        }
        if (doc.getDocVersion() == null || doc.getDocVersion().isBlank()) {
            missing.add("규정 버전");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "확정하려면 " + String.join("·", missing) + "을(를) 입력해 주세요.");
        }
        if (clauses.countByDocIdAndKind(docId, RuleTextSplitter.Kind.ARTICLE) == 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "조항이 하나도 없는 규정은 확정할 수 없습니다.");
        }
        long unreviewed = clauses.countByDocIdAndReviewStatusAndKindIn(docId, ClauseReviewStatus.UNREVIEWED, REVIEW_KINDS);
        if (unreviewed > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "아직 검수하지 않은 조항이 " + unreviewed + "개 있습니다.");
        }
        Map<String, Object> before = metadataSnapshot(doc);
        doc.confirm(access.userId(), clock.instant());
        docs.flush();
        audit.record(access.userId(), doc.getOrgId(), "RULE_DOC_CONFIRMED", "RULE_DOCUMENT", docId, before,
                metadataSnapshot(doc));
        return summary(doc);
    }

    /** 확정한 규정을 더 쓰지 않기로 한다(새 버전을 올린 뒤 옛 버전 정리). 기록은 남는다. */
    @Transactional
    public DocSummary retire(CurrentUser user, long docId, String ifMatch) {
        RuleDocument doc = lockedDoc(docId);
        OrgAccess access = requireOwnerOf(user, doc);
        IfMatch.check(ifMatch, doc.getVersion());
        Map<String, Object> before = metadataSnapshot(doc);
        doc.retire();
        docs.flush();
        audit.record(access.userId(), doc.getOrgId(), "RULE_DOC_RETIRED", "RULE_DOCUMENT", docId, before,
                metadataSnapshot(doc));
        return summary(doc);
    }

    /** 확정 전(DRAFT) 규정을 통째로 지운다. 확정한 규정은 지우지 않고 폐기만 한다. */
    @Transactional
    public void deleteDraft(CurrentUser user, long docId) {
        RuleDocument doc = lockedDoc(docId);
        OrgAccess access = requireOwnerOf(user, doc);
        doc.requireDraft();
        Map<String, Object> before = metadataSnapshot(doc);
        clauses.deleteByDocId(docId);
        docs.delete(doc);
        audit.record(access.userId(), doc.getOrgId(), "RULE_DOC_DELETED", "RULE_DOCUMENT", docId, before, null);
    }

    // ---- 내부 ----

    private RuleDocument load(long docId) {
        return docs.findById(docId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 그룹이 올린 규정은 그 그룹 회장만 고친다. 서비스가 넣은 공통 규정은 화면에서 고칠 수 없다. */
    private OrgAccess requireOwnerOf(CurrentUser user, RuleDocument doc) {
        if (doc.isGlobal()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "서비스가 제공하는 공통 규정은 고치거나 지울 수 없습니다.");
        }
        return accessGuard.requireOwner(user, doc.getOrgId());
    }

    private RuleDocument lockedDoc(long docId) {
        return docs.findByIdForUpdate(docId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Clause loadClause(long clauseId) {
        return clauses.findById(clauseId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private DocSummary summary(RuleDocument d) {
        long articles = clauses.countByDocIdAndKind(d.getId(), RuleTextSplitter.Kind.ARTICLE);
        long unreviewed = clauses.countByDocIdAndReviewStatusAndKindIn(d.getId(), ClauseReviewStatus.UNREVIEWED, REVIEW_KINDS);
        return new DocSummary(d.getId(), d.getTitle(), d.getSourceType(), d.getIssuer(), d.getEffectiveDate(),
                d.getDocVersion(), d.getStatus(), articles, unreviewed, d.isRoundtripOk(), d.getVersion());
    }

    private DocDetail detail(RuleDocument d, List<String> warnings) {
        List<ClauseView> views = clauses.findByDocIdOrderBySeq(d.getId()).stream().map(ClauseView::of).toList();
        return new DocDetail(summary(d), d.getAppliesTo(), d.getNotes(), d.getFileName(), d.getSourceSha256(),
                d.getReviewedBy(), d.getReviewedAt(), warnings, views);
    }

    private static Map<String, Object> registrationSnapshot(RuleDocument d, int pieces, long articles) {
        Map<String, Object> m = metadataSnapshot(d);
        m.put("pieceCount", pieces);
        m.put("articleCount", articles);
        m.put("roundtripOk", d.isRoundtripOk());
        m.put("sourceSha256", d.getSourceSha256());
        return m;
    }

    private static Map<String, Object> metadataSnapshot(RuleDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", d.getTitle());
        m.put("issuer", d.getIssuer());
        m.put("appliesTo", d.getAppliesTo());
        m.put("effectiveDate", d.getEffectiveDate() == null ? null : d.getEffectiveDate().toString());
        m.put("docVersion", d.getDocVersion());
        m.put("status", d.getStatus().name());
        return m;
    }

    private static Map<String, Object> clauseSnapshot(Clause c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", c.getKind().name());
        m.put("label", c.getLabel());
        m.put("title", c.getTitle());
        m.put("text", c.getText());
        m.put("reviewStatus", c.getReviewStatus().name());
        return m;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + "을(를) 입력해 주세요.");
        }
        return optional(value, field, max);
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + "은(는) " + max + "자 이하로 입력해 주세요.");
        }
        return trimmed;
    }

    /** 조항 본문은 앞뒤 공백도 그대로 두되, 비었거나 너무 길면 거절한다. */
    private static String clauseText(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조항 본문을 입력해 주세요.");
        }
        if (value.length() > MAX_CLAUSE_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "조항 본문이 너무 깁니다.");
        }
        return value;
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "날짜는 2026-03-01 모양으로 입력해 주세요.");
        }
    }

    private static String trimName(String name) {
        if (name == null) {
            return null;
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
