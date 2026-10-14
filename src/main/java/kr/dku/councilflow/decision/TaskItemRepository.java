package kr.dku.councilflow.decision;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {

    /** 여러 업무의 체크리스트를 한 번에(업무보드용) */
    @Query("""
            select i from TaskItem i
              left join fetch i.assignee
             where i.task.id in :taskIds
             order by i.id
            """)
    List<TaskItem> findByTaskIds(Collection<Long> taskIds);

    long countByTaskIdIn(Collection<Long> taskIds);

    /** 업무를 지울 때 그 업무의 체크리스트도 지운다. */
    @Modifying
    @Query("delete from TaskItem i where i.task.id in :taskIds")
    int deleteByTaskIds(Collection<Long> taskIds);
}
