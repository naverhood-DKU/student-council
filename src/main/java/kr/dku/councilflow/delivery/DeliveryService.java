package kr.dku.councilflow.delivery;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.user.AppUserRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 조직 간 전달(단계 2). 범위는 한 단계: 결정을 낸 조직 → 직속 하위 조직.
 *
 * 누가 무엇을 하나
 * - 보낸 조직의 회의 책임자: 전달 만들기, 기한 변경, 확인 요청에 답변, 취소, 결과 확정·반려
 * - 받은 조직의 회의 책임자·조직 관리자: 수락, 확인 요청(사유 필수), 해당 없음, 내부 담당 부서 지정, 시작, 결과 제출
 * - 받은 조직에서 담당 부서로 지정된 부서원: 시작, 결과 제출
 * - 보낸 조직 소속은 자기가 보낸 전달과 그 제출물만, 받은 조직 소속은 받은 것만 본다.
 */
@Service
public class DeliveryService {

    private static final MembershipRole[] RECEIVER_MANAGERS = {MembershipRole.MEETING_CHAIR, MembershipRole.ORG_ADMIN};

    private final DeliveryRepository deliveries;
    private final SubmissionRepository submissions;
    private final DecisionRepository decisions;
    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final AppUserRepository users;
    private final OrgAccessGuard accessGuard;
    private final DeliveryViews views;
    private final AuditService audit;
    private final Clock clock;

    DeliveryService(DeliveryRepository deliveries, SubmissionRepository submissions, DecisionRepository decisions,
                    OrganizationRepository organizations, DepartmentRepository departments, AppUserRepository users,
                    OrgAccessGuard accessGuard, DeliveryViews views, AuditService audit, Clock clock) {
        this.deliveries = deliveries;
        this.submissions = submissions;
        this.decisions = decisions;
        this.organizations = organizations;
        this.departments = departments;
        this.users = users;
        this.accessGuard = accessGuard;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateDeliveries(
            @NotEmpty @Size(max = 50) List<Long> targetOrgIds,
            @NotNull RequestType requestType,
            LocalDate dueDate,
            @Size(max = 2000) String message) {
    }

    public record CreateSubmission(@Size(max = 1000) String resultLink, @Size(max = 2000) String description) {
    }

    public record Review(@NotNull Boolean approve, @Size(max = 2000) String comment) {
    }

    /** 결정 하나의 전달 현황(보낸 조직 화면) */
    public record DecisionDeliveries(
            long decisionId,
            String agenda,
            String summary,
            long fromOrgId,
            DeliveryViews.Tally tally,
            List<DeliveryViews.DeliveryView> targets) {
    }

    /** 보낸 전달을 결정별로 묶은 목록(보낸 요청 화면) */
    public record SentDecision(long decisionId, String agenda, String summary, long meetingId, String meetingTitle,
                               DeliveryViews.Tally tally) {
    }

    // ── 보내기 ──

    @Transactional
    public DecisionDeliveries send(CurrentUser user, long decisionId, CreateDeliveries request) {
        Decision decision = decisions.findById(decisionId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Organization from = decision.getMeeting().getOrganization();
        accessGuard.requireRole(user, from.getId(), MembershipRole.MEETING_CHAIR);
        if (decision.getStatus() != DecisionStatus.APPROVED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "승인된 결정만 전달할 수 있습니다.");
        }

        List<Organization> targets = new ArrayList<>();
        for (Long targetId : new LinkedHashSet<>(request.targetOrgIds())) {
            Organization target = organizations.findById(targetId)
                    .filter(Organization::isActive)
                    .filter(o -> o.getParent() != null && o.getParent().getId().equals(from.getId()))
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                            "직속 하위 조직에만 전달할 수 있습니다."));
            targets.add(target);
        }
        List<String> already = targets.stream()
                .filter(t -> deliveries.existsByDecisionIdAndToOrganizationId(decisionId, t.getId()))
                .map(Organization::getName)
                .toList();
        if (!already.isEmpty()) {
            throw new ApiException(ErrorCode.DUPLICATE, "이미 전달한 조직이 있습니다: " + String.join(", ", already));
        }

        String message = blankToNull(request.message());
        for (Organization target : targets) {
            Delivery delivery = deliveries.save(Delivery.send(decision, from, target, request.requestType(),
                    request.dueDate(), message, users.getReferenceById(user.id())));
            audit.record(user.id(), from.getId(), "DELIVERY_SENT", "DELIVERY", delivery.getId(), null,
                    snapshot(delivery));
        }
        deliveries.flush();
        return decisionDeliveries(decision);
    }

    // ── 상태·담당·기한 바꾸기 ──

    /**
     * 본문에 있는 항목만 바꾼다. If-Match 필요.
     * { "status": "ACCEPTED" | "CLARIFICATION_REQUESTED" | "NOT_APPLICABLE" | "IN_PROGRESS" | "PENDING" | "CANCELLED",
     *   "reason": "...",                 // 확인 요청(필수)·해당 없음·취소 사유
     *   "clarificationReply": "...",     // 보낸 조직이 확인 요청에 답할 때(status PENDING 과 함께, 필수)
     *   "internalDepartmentId": 3 | null,
     *   "dueDate": "2026-10-25" | null }
     */
    @Transactional
    public DeliveryViews.DeliveryView update(CurrentUser user, long deliveryId, String ifMatch, JsonNode body) {
        Delivery delivery = deliveries.findById(deliveryId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Actor actor = actorOf(user, delivery);
        IfMatch.check(ifMatch, delivery.getVersion());
        if (body == null || !body.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "바꿀 내용을 JSON 객체로 보내 주세요.");
        }
        Map<String, Object> before = snapshot(delivery);

        if (body.has("internalDepartmentId")) {
            actor.requireReceiverManager();
            delivery.assignInternalDepartment(internalDepartment(body.get("internalDepartmentId"), delivery));
        }
        if (body.has("dueDate")) {
            actor.requireSenderChair();
            delivery.changeDueDate(date(body.get("dueDate")), clock.instant());
        }
        if (body.has("status")) {
            changeStatus(delivery, actor, status(body.get("status")), text(body, "reason"),
                    text(body, "clarificationReply"));
        }

        deliveries.flush();
        audit.record(user.id(), actor.actingOrgId(), "DELIVERY_UPDATED", "DELIVERY", deliveryId, before,
                snapshot(delivery));
        return views.of(delivery);
    }

    private static void changeStatus(Delivery delivery, Actor actor, DeliveryStatus target, String reason,
                                     String reply) {
        switch (target) {
            case ACCEPTED -> {
                actor.requireReceiverManager();
                delivery.accept();
            }
            case CLARIFICATION_REQUESTED -> {
                actor.requireReceiverManager();
                if (reason == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "확인 요청 사유를 적어 주세요.");
                }
                delivery.requestClarification(reason);
            }
            case NOT_APPLICABLE -> {
                actor.requireReceiverManager();
                delivery.markNotApplicable(reason);
            }
            case IN_PROGRESS -> {
                if (delivery.getStatus() == DeliveryStatus.SUBMITTED) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "반려는 제출물 검토(review)로 합니다.");
                }
                actor.requireReceiverWorker(delivery);
                delivery.start();
            }
            case PENDING -> {
                actor.requireSenderChair();
                if (reply == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "확인 요청에 대한 답변을 적어 주세요.");
                }
                delivery.answerClarification(reply);
            }
            case CANCELLED -> {
                actor.requireSenderChair();
                delivery.cancel(reason);
            }
            case SUBMITTED, DONE -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "결과 제출은 submissions, 완료 확정은 review 로 합니다.");
        }
    }

    // ── 결과 제출·검토 ──

    @Transactional
    public DeliveryViews.DeliveryView submit(CurrentUser user, long deliveryId, CreateSubmission request) {
        Delivery delivery = deliveries.findById(deliveryId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Actor actor = actorOf(user, delivery);
        actor.requireReceiverWorker(delivery);
        String link = blankToNull(request.resultLink());
        String description = blankToNull(request.description());
        if (link == null && description == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "결과 링크나 설명 중 하나는 적어 주세요.");
        }
        if (link != null && !isHttpUrl(link)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "결과 링크는 http:// 또는 https:// 로 시작해야 합니다.");
        }
        delivery.submitted();
        Submission submission = submissions.save(
                new Submission(delivery, link, description, users.getReferenceById(user.id())));
        deliveries.flush();
        audit.record(user.id(), delivery.getToOrganization().getId(), "SUBMISSION_CREATED", "SUBMISSION",
                submission.getId(), null, Map.of("deliveryId", deliveryId));
        return views.of(delivery);
    }

    /** 보낸 조직이 제출물을 확정(→ DONE)하거나 반려(→ IN_PROGRESS, 의견 필수)한다. */
    @Transactional
    public DeliveryViews.DeliveryView review(CurrentUser user, long submissionId, Review request) {
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Delivery delivery = submission.getDelivery();
        actorOf(user, delivery).requireSenderChair();
        String comment = blankToNull(request.comment());
        if (!request.approve() && comment == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "반려할 때는 이유를 적어 주세요.");
        }
        if (delivery.getStatus() != DeliveryStatus.SUBMITTED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "검토를 기다리는 제출물이 아닙니다.");
        }
        Map<String, Object> before = snapshot(delivery);
        submission.review(request.approve(), comment, users.getReferenceById(user.id()), clock.instant());
        if (request.approve()) {
            delivery.approveResult();
        } else {
            delivery.returnResult();
        }
        deliveries.flush();
        audit.record(user.id(), delivery.getFromOrganization().getId(),
                request.approve() ? "SUBMISSION_APPROVED" : "SUBMISSION_REJECTED", "SUBMISSION", submissionId,
                before, snapshot(delivery));
        return views.of(delivery);
    }

    // ── 조회 ──

    /** 수신함: 이 조직이 받은 전달. 이 조직 소속만 본다. */
    @Transactional(readOnly = true)
    public List<DeliveryViews.DeliveryView> inbox(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return views.of(deliveries.findReceivedBy(orgId));
    }

    /** 결정 하나의 대상별 상태와 집계. 보낸 조직 소속만 본다(받은 조직은 403). */
    @Transactional(readOnly = true)
    public DecisionDeliveries summary(CurrentUser user, long decisionId) {
        Decision decision = decisions.findById(decisionId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        accessGuard.requireMember(user, decision.getMeeting().getOrgId());
        return decisionDeliveries(decision);
    }

    /** 보낸 요청: 이 조직이 보낸 전달을 결정별로 묶어 집계한다. */
    @Transactional(readOnly = true)
    public List<SentDecision> sent(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        Map<Decision, List<Delivery>> byDecision = deliveries.findSentBy(orgId).stream()
                .collect(Collectors.groupingBy(Delivery::getDecision, LinkedHashMap::new, Collectors.toList()));
        return byDecision.entrySet().stream()
                .map(e -> new SentDecision(e.getKey().getId(), e.getKey().getAgenda(), e.getKey().getSummary(),
                        e.getKey().getMeeting().getId(), e.getKey().getMeeting().getTitle(),
                        DeliveryViews.Tally.of(e.getValue())))
                .toList();
    }

    private DecisionDeliveries decisionDeliveries(Decision decision) {
        List<Delivery> sent = deliveries.findByDecision(decision.getId());
        return new DecisionDeliveries(decision.getId(), decision.getAgenda(), decision.getSummary(),
                decision.getMeeting().getOrgId(), DeliveryViews.Tally.of(sent), views.of(sent));
    }

    // ── 누가 하는지 ──

    /** 이 전달에 대해 사용자가 보낸 쪽인지 받은 쪽인지. 둘 다 아니면 403. */
    private Actor actorOf(CurrentUser user, Delivery delivery) {
        Optional<OrgAccess> sender = accessGuard.find(user, delivery.getFromOrganization().getId());
        Optional<OrgAccess> receiver = accessGuard.find(user, delivery.getToOrganization().getId());
        if (sender.isEmpty() && receiver.isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return new Actor(sender.orElse(null), receiver.orElse(null));
    }

    private record Actor(OrgAccess sender, OrgAccess receiver) {

        void requireSenderChair() {
            if (sender == null || !sender.hasAnyRole(MembershipRole.MEETING_CHAIR)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "보낸 조직의 회의 책임자만 할 수 있습니다.");
            }
        }

        void requireReceiverManager() {
            if (receiver == null || !receiver.hasAnyRole(RECEIVER_MANAGERS)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "받은 조직의 회의 책임자나 조직 관리자만 할 수 있습니다.");
            }
        }

        /** 받은 조직의 관리자, 또는 내부 담당 부서로 지정된 부서의 소속 */
        void requireReceiverWorker(Delivery delivery) {
            boolean manager = receiver != null && receiver.hasAnyRole(RECEIVER_MANAGERS);
            Department assigned = delivery.getInternalDepartment();
            boolean assignedMember = receiver != null && assigned != null
                    && receiver.belongsToDepartment(assigned.getId());
            if (!manager && !assignedMember) {
                throw new ApiException(ErrorCode.FORBIDDEN, "받은 조직의 관리자나 담당 부서만 할 수 있습니다.");
            }
        }

        long actingOrgId() {
            return receiver != null ? receiver.orgId() : sender.orgId();
        }
    }

    // ── 입력 읽기 ──

    private Department internalDepartment(JsonNode node, Delivery delivery) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "internalDepartmentId 는 숫자이거나 null 이어야 합니다.");
        }
        long receiverOrgId = delivery.getToOrganization().getId();
        return departments.findById(node.asLong())
                .filter(d -> d.getOrganization().getId() == receiverOrgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "받은 조직의 부서가 아닙니다."));
    }

    private static DeliveryStatus status(JsonNode node) {
        try {
            return DeliveryStatus.valueOf(node.asString());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "알 수 없는 전달 상태입니다.");
        }
    }

    private static LocalDate date(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asString());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "기한은 YYYY-MM-DD 형식이어야 합니다.");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().strip();
        if (value.length() > 2000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " 는 2000자 이하로 적어 주세요.");
        }
        return value.isEmpty() ? null : value;
    }

    private static boolean isHttpUrl(String link) {
        try {
            URI uri = URI.create(link);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Object> snapshot(Delivery delivery) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", delivery.getStatus());
        map.put("dueDate", delivery.getDueDate() == null ? null : delivery.getDueDate().toString());
        map.put("internalDepartmentId",
                delivery.getInternalDepartment() == null ? null : delivery.getInternalDepartment().getId());
        return map;
    }
}
