package kr.dku.councilflow.delivery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 전달을 화면용 모양으로 바꾼다. 보낸 쪽·받은 쪽이 같은 모양을 쓴다.
 * 받는 조직에 보이는 것은 결정 안건·요약·요청 사항뿐이다. 보낸 조직의 회의 id·회의록은 넣지 않는다(원칙 8).
 */
@Component
public class DeliveryViews {

    private final SubmissionRepository submissions;

    DeliveryViews(SubmissionRepository submissions) {
        this.submissions = submissions;
    }

    public record SubmissionView(
            long id,
            String resultLink,
            String description,
            String submittedByName,
            Submission.ReviewStatus reviewStatus,
            String reviewComment,
            String reviewedByName,
            Instant createdAt,
            Instant reviewedAt) {

        static SubmissionView of(Submission s) {
            return new SubmissionView(s.getId(), s.getResultLink(), s.getDescription(), s.getSubmittedBy().getName(),
                    s.getReviewStatus(), s.getReviewComment(),
                    s.getReviewedBy() == null ? null : s.getReviewedBy().getName(), s.getCreatedAt(),
                    s.getReviewedAt());
        }
    }

    public record DeliveryView(
            long id,
            long decisionId,
            String decisionAgenda,
            String decisionSummary,
            long fromOrgId,
            String fromOrgName,
            long toOrgId,
            String toOrgName,
            RequestType requestType,
            DeliveryStatus status,
            LocalDate dueDate,
            LocalDate previousDueDate,
            Instant dueChangedAt,
            String message,
            Long internalDepartmentId,
            String internalDepartmentName,
            String clarificationReason,
            String clarificationReply,
            String closingReason,
            Instant createdAt,
            long version,
            /** 새 것부터 */
            List<SubmissionView> submissions) {
    }

    /**
     * 대상별 상태 집계. total(분모)은 해당 없음·취소를 뺀 유효 대상 수다.
     * counts 에는 모든 상태가 0 이상으로 들어간다.
     */
    public record Tally(long total, Map<DeliveryStatus, Long> counts) {

        static Tally of(Collection<Delivery> deliveries) {
            Map<DeliveryStatus, Long> counts = new EnumMap<>(DeliveryStatus.class);
            for (DeliveryStatus status : DeliveryStatus.values()) {
                counts.put(status, 0L);
            }
            deliveries.forEach(d -> counts.merge(d.getStatus(), 1L, Long::sum));
            long total = deliveries.stream().filter(d -> d.getStatus().countsTowardTotal()).count();
            return new Tally(total, counts);
        }
    }

    public List<DeliveryView> of(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SubmissionView>> byDelivery = submissions
                .findByDeliveryIds(deliveries.stream().map(Delivery::getId).toList()).stream()
                .collect(Collectors.groupingBy(s -> s.getDelivery().getId(),
                        Collectors.mapping(SubmissionView::of, Collectors.toList())));
        return deliveries.stream().map(d -> view(d, byDelivery.getOrDefault(d.getId(), List.of()))).toList();
    }

    public DeliveryView of(Delivery delivery) {
        return of(List.of(delivery)).get(0);
    }

    private static DeliveryView view(Delivery d, List<SubmissionView> submissionViews) {
        return new DeliveryView(d.getId(), d.getDecision().getId(), d.getDecision().getAgenda(),
                d.getDecision().getSummary(), d.getFromOrganization().getId(), d.getFromOrganization().getName(),
                d.getToOrganization().getId(), d.getToOrganization().getName(), d.getRequestType(), d.getStatus(),
                d.getDueDate(), d.getPreviousDueDate(), d.getDueChangedAt(), d.getMessage(),
                d.getInternalDepartment() == null ? null : d.getInternalDepartment().getId(),
                d.getInternalDepartment() == null ? null : d.getInternalDepartment().getName(),
                d.getClarificationReason(), d.getClarificationReply(), d.getClosingReason(), d.getCreatedAt(),
                d.getVersion(), submissionViews);
    }
}
