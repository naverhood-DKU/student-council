package kr.dku.councilflow.expense;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseReviewRepository extends JpaRepository<ExpenseReview, Long> {

    List<ExpenseReview> findByOrgIdOrderByIdDesc(Long orgId);
}
