package kr.dku.councilflow.decision;

import java.util.EnumSet;
import java.util.Set;

/**
 * 결정 상태와 허용 전이. 전이 규칙은 여기 한 곳에만 둔다.
 *
 * PROPOSED(AI 후보) → APPROVED / ON_HOLD
 * ON_HOLD           → APPROVED / CHANGED / CANCELLED
 * APPROVED          → CHANGED / CANCELLED
 * CHANGED, CANCELLED 는 끝.
 * 변경 주체는 회의를 연 그룹의 owner(회장).
 */
public enum DecisionStatus {
    PROPOSED,
    APPROVED,
    ON_HOLD,
    CHANGED,
    CANCELLED;

    public Set<DecisionStatus> next() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, ON_HOLD);
            case ON_HOLD -> EnumSet.of(APPROVED, CHANGED, CANCELLED);
            case APPROVED -> EnumSet.of(CHANGED, CANCELLED);
            case CHANGED, CANCELLED -> EnumSet.noneOf(DecisionStatus.class);
        };
    }

    public boolean canMoveTo(DecisionStatus target) {
        return next().contains(target);
    }

    /** 승인 전이라 내용(요약·담당·기한)을 고칠 수 있는 상태 */
    public boolean isEditableProposal() {
        return this == PROPOSED || this == ON_HOLD;
    }
}
