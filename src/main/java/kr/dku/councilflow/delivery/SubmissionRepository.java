package kr.dku.councilflow.delivery;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    @Query("""
            select s from Submission s
              join fetch s.submittedBy
              left join fetch s.reviewedBy
             where s.delivery.id in :deliveryIds
             order by s.id desc
            """)
    List<Submission> findByDeliveryIds(Collection<Long> deliveryIds);

    /** 회의를 지울 때 그 결정으로 보낸 요청의 제출 결과도 지운다. */
    @Modifying
    @Query("delete from Submission s where s.delivery.id in :deliveryIds")
    int deleteByDeliveryIds(Collection<Long> deliveryIds);
}
