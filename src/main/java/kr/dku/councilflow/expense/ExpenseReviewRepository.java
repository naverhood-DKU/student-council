package kr.dku.councilflow.expense;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseReviewRepository extends JpaRepository<ExpenseReview, Long> {

    List<ExpenseReview> findByOrgIdOrderByIdDesc(Long orgId);

    /** 업무 카드마다 자동으로 확인한 검토. 같은 카드에 여러 건이면 id 가 큰(최근) 것을 쓴다. */
    List<ExpenseReview> findByTaskIdInOrderByIdAsc(Collection<Long> taskIds);

    List<ExpenseReview> findByDecisionIdIn(Collection<Long> decisionIds);

    /** 회의를 지울 때 그 결정에서 사람이 요청한 지출 검토는 남기고 결정과의 연결만 끊는다. */
    @Modifying
    @Query("update ExpenseReview r set r.decisionId = null where r.decisionId in :decisionIds")
    int detachDecisions(Collection<Long> decisionIds);
}
