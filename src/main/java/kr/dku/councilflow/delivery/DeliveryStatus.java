package kr.dku.councilflow.delivery;

import java.util.EnumSet;
import java.util.Set;

/**
 * 전달(Delivery) 상태와 허용 전이. 전이 규칙은 여기 한 곳에만 둔다.
 *
 * PENDING                 → ACCEPTED(수락) / CLARIFICATION_REQUESTED(확인 요청) / NOT_APPLICABLE(해당 없음) / CANCELLED(발신 취소)
 * CLARIFICATION_REQUESTED → PENDING(발신 조직이 답변) / ACCEPTED / NOT_APPLICABLE / CANCELLED
 * ACCEPTED                → IN_PROGRESS / DONE(알림 INFORM 은 수락하면 끝) / CANCELLED
 * IN_PROGRESS             → SUBMITTED(결과 제출) / CANCELLED
 * SUBMITTED               → DONE(완료 확정) / IN_PROGRESS(반려) / CANCELLED
 * DONE, NOT_APPLICABLE, CANCELLED 는 끝.
 */
public enum DeliveryStatus {
    PENDING,
    CLARIFICATION_REQUESTED,
    ACCEPTED,
    IN_PROGRESS,
    SUBMITTED,
    DONE,
    NOT_APPLICABLE,
    CANCELLED;

    public Set<DeliveryStatus> next() {
        return switch (this) {
            case PENDING -> EnumSet.of(ACCEPTED, CLARIFICATION_REQUESTED, NOT_APPLICABLE, CANCELLED);
            case CLARIFICATION_REQUESTED -> EnumSet.of(PENDING, ACCEPTED, NOT_APPLICABLE, CANCELLED);
            case ACCEPTED -> EnumSet.of(IN_PROGRESS, DONE, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(SUBMITTED, CANCELLED);
            case SUBMITTED -> EnumSet.of(DONE, IN_PROGRESS, CANCELLED);
            case DONE, NOT_APPLICABLE, CANCELLED -> EnumSet.noneOf(DeliveryStatus.class);
        };
    }

    public boolean canMoveTo(DeliveryStatus target) {
        return next().contains(target);
    }

    public boolean isFinished() {
        return next().isEmpty();
    }

    /** 집계 분모에 들어가는지. 해당 없음·취소는 빼고, 취소를 완료로 세지 않는다. */
    public boolean countsTowardTotal() {
        return this != NOT_APPLICABLE && this != CANCELLED;
    }
}
