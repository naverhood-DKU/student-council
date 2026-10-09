package kr.dku.councilflow.join;

import java.util.EnumSet;
import java.util.Set;

/**
 * 가입 신청 상태와 허용 전이. 전이 규칙은 여기 한 곳에만 둔다.
 *
 * PENDING  → APPROVED(회장이 역할·부서를 정해 승인) / REJECTED(거절)
 * REJECTED → PENDING  (다시 신청)
 * APPROVED → PENDING  (소속이 끊긴 뒤 다시 신청)
 */
public enum JoinRequestStatus {
    PENDING,
    APPROVED,
    REJECTED;

    public Set<JoinRequestStatus> next() {
        return switch (this) {
            case PENDING -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.of(PENDING);
        };
    }

    public boolean canMoveTo(JoinRequestStatus target) {
        return next().contains(target);
    }
}
