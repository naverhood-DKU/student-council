package kr.dku.councilflow.decision;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByDecisionIdInOrderById(Collection<Long> decisionIds);

    List<Task> findByDecisionIdOrderById(Long decisionId);

    /** 업무보드: 그 조직 회의에서 나온 업무 중 승인된 것(PROPOSED 제외). */
    @Query("""
            select t from Task t
              join fetch t.decision d
              join fetch d.meeting m
              left join fetch t.department
             where m.organization.id = :orgId and t.status <> kr.dku.councilflow.decision.TaskStatus.PROPOSED
             order by t.dueDate asc nulls last, t.id
            """)
    List<Task> findBoardTasks(Long orgId);
}
