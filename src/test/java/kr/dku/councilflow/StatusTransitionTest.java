package kr.dku.councilflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.decision.TaskStatus;
import kr.dku.councilflow.delivery.DeliveryStatus;
import kr.dku.councilflow.job.JobStatus;
import org.junit.jupiter.api.Test;

/**
 * 상태 전이 표 전체 확인. 모든 (현재, 다음) 쌍에 대해 허용 여부가 아래 표와 같아야 한다.
 * 표를 바꾸면 CLAUDE.md 의 "상태 흐름"도 같이 고친다.
 */
class StatusTransitionTest {

    @Test
    void 결정_전이표() {
        Map<DecisionStatus, Set<DecisionStatus>> expected = Map.of(
                DecisionStatus.PROPOSED, EnumSet.of(DecisionStatus.APPROVED, DecisionStatus.ON_HOLD),
                DecisionStatus.ON_HOLD, EnumSet.of(DecisionStatus.APPROVED, DecisionStatus.CHANGED,
                        DecisionStatus.CANCELLED),
                DecisionStatus.APPROVED, EnumSet.of(DecisionStatus.CHANGED, DecisionStatus.CANCELLED),
                DecisionStatus.CHANGED, EnumSet.noneOf(DecisionStatus.class),
                DecisionStatus.CANCELLED, EnumSet.noneOf(DecisionStatus.class));
        for (DecisionStatus from : DecisionStatus.values()) {
            for (DecisionStatus to : DecisionStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected.get(from).contains(to));
            }
        }
    }

    @Test
    void 업무_전이표() {
        Map<TaskStatus, Set<TaskStatus>> expected = Map.of(
                TaskStatus.PROPOSED, EnumSet.of(TaskStatus.UNASSIGNED, TaskStatus.ASSIGNED, TaskStatus.CANCELLED),
                TaskStatus.UNASSIGNED, EnumSet.of(TaskStatus.ASSIGNED, TaskStatus.CANCELLED),
                TaskStatus.ASSIGNED, EnumSet.of(TaskStatus.UNASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED),
                TaskStatus.IN_PROGRESS, EnumSet.of(TaskStatus.SUBMITTED, TaskStatus.CANCELLED),
                TaskStatus.SUBMITTED, EnumSet.of(TaskStatus.DONE, TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED),
                TaskStatus.DONE, EnumSet.noneOf(TaskStatus.class),
                TaskStatus.CANCELLED, EnumSet.noneOf(TaskStatus.class));
        for (TaskStatus from : TaskStatus.values()) {
            for (TaskStatus to : TaskStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected.get(from).contains(to));
            }
        }
    }

    @Test
    void 전달_전이표() {
        Map<DeliveryStatus, Set<DeliveryStatus>> expected = Map.of(
                DeliveryStatus.PENDING, EnumSet.of(DeliveryStatus.ACCEPTED, DeliveryStatus.CLARIFICATION_REQUESTED,
                        DeliveryStatus.NOT_APPLICABLE, DeliveryStatus.CANCELLED),
                DeliveryStatus.CLARIFICATION_REQUESTED, EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.ACCEPTED,
                        DeliveryStatus.NOT_APPLICABLE, DeliveryStatus.CANCELLED),
                DeliveryStatus.ACCEPTED, EnumSet.of(DeliveryStatus.IN_PROGRESS, DeliveryStatus.DONE,
                        DeliveryStatus.CANCELLED),
                DeliveryStatus.IN_PROGRESS, EnumSet.of(DeliveryStatus.SUBMITTED, DeliveryStatus.CANCELLED),
                DeliveryStatus.SUBMITTED, EnumSet.of(DeliveryStatus.DONE, DeliveryStatus.IN_PROGRESS,
                        DeliveryStatus.CANCELLED),
                DeliveryStatus.DONE, EnumSet.noneOf(DeliveryStatus.class),
                DeliveryStatus.NOT_APPLICABLE, EnumSet.noneOf(DeliveryStatus.class),
                DeliveryStatus.CANCELLED, EnumSet.noneOf(DeliveryStatus.class));
        for (DeliveryStatus from : DeliveryStatus.values()) {
            for (DeliveryStatus to : DeliveryStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected.get(from).contains(to));
            }
        }
    }

    @Test
    void 작업_전이표() {
        Map<JobStatus, Set<JobStatus>> expected = Map.of(
                JobStatus.QUEUED, EnumSet.of(JobStatus.RUNNING, JobStatus.FAILED),
                JobStatus.RUNNING, EnumSet.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.FAILED_UNKNOWN,
                        JobStatus.QUEUED, JobStatus.RUNNING),
                JobStatus.SUCCEEDED, EnumSet.noneOf(JobStatus.class),
                JobStatus.FAILED, EnumSet.noneOf(JobStatus.class),
                JobStatus.FAILED_UNKNOWN, EnumSet.noneOf(JobStatus.class));
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected.get(from).contains(to));
            }
        }
    }
}
