package kr.dku.councilflow.expense;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseReviewRepository extends JpaRepository<ExpenseReview, Long> {

    List<ExpenseReview> findByOrgIdOrderByIdDesc(Long orgId);

    /** 업무 카드마다 자동으로 확인한 검토. 같은 카드에 여러 건이면 id 가 큰(최근) 것을 쓴다. */
    List<ExpenseReview> findByTaskIdInOrderByIdAsc(Collection<Long> taskIds);

    List<ExpenseReview> findByDecisionIdIn(Collection<Long> decisionIds);
}
