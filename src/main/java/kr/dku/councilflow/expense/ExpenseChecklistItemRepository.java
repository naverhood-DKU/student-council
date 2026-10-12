package kr.dku.councilflow.expense;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseChecklistItemRepository extends JpaRepository<ExpenseChecklistItem, Long> {

    List<ExpenseChecklistItem> findByReviewIdOrderBySeq(Long reviewId);

    void deleteByReviewId(Long reviewId);
}
