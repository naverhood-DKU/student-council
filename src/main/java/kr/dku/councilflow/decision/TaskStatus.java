package kr.dku.councilflow.decision;

import java.util.EnumSet;
import java.util.Set;

/**
 * 업무 상태와 허용 전이. 전이 규칙은 여기 한 곳에만 둔다.
 *
 * PROPOSED(AI 후보, 업무보드에 안 보임) → UNASSIGNED / ASSIGNED   (결정 승인 때 서버가 바꿈)
 *                                     → CANCELLED                (승인 전에 후보에서 뺌)
 * UNASSIGNED  → ASSIGNED                                         (담당 부서 지정)
 * ASSIGNED    → UNASSIGNED(담당 해제) / IN_PROGRESS
 * IN_PROGRESS → SUBMITTED                                        (결과 제출)
 * SUBMITTED   → DONE(완료 확정) / IN_PROGRESS(반려)
 * 끝나지 않은 업무 → CANCELLED (회의 책임자가 업무 취소)
 * DONE, CANCELLED 는 끝. 끝난 업무는 내용도 고칠 수 없다.
 */
public enum TaskStatus {
    PROPOSED,
    UNASSIGNED,
    ASSIGNED,
    IN_PROGRESS,
    SUBMITTED,
    DONE,
    CANCELLED;

    public Set<TaskStatus> next() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(UNASSIGNED, ASSIGNED, CANCELLED);
            case UNASSIGNED -> EnumSet.of(ASSIGNED, CANCELLED);
            case ASSIGNED -> EnumSet.of(UNASSIGNED, IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(SUBMITTED, CANCELLED);
            case SUBMITTED -> EnumSet.of(DONE, IN_PROGRESS, CANCELLED);
            case DONE, CANCELLED -> EnumSet.noneOf(TaskStatus.class);
        };
    }

    public boolean canMoveTo(TaskStatus target) {
        return next().contains(target);
    }

    public boolean isFinished() {
        return this == DONE || this == CANCELLED;
    }
}
