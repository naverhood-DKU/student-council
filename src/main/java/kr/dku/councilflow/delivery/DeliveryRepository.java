package kr.dku.councilflow.delivery;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    String FETCH = """
            select d from Delivery d
              join fetch d.decision
              join fetch d.fromOrganization
              join fetch d.toOrganization
              left join fetch d.internalDepartment
            """;

    @Query(FETCH + " where d.decision.id = :decisionId order by d.toOrganization.id")
    List<Delivery> findByDecision(Long decisionId);

    /** 수신함: 이 조직이 받은 전달 */
    @Query(FETCH + " where d.toOrganization.id = :orgId order by d.id desc")
    List<Delivery> findReceivedBy(Long orgId);

    /** 이 조직이 보낸 전달 */
    @Query(FETCH + " where d.fromOrganization.id = :orgId order by d.decision.id desc, d.toOrganization.id")
    List<Delivery> findSentBy(Long orgId);

    boolean existsByDecisionIdAndToOrganizationId(Long decisionId, Long toOrgId);

    /** 받은 그룹 안에서 그 부서가 맡은 전달 중 상태가 statuses 가 아닌 것의 수 */
    long countByInternalDepartmentIdAndStatusNotIn(Long departmentId, Collection<DeliveryStatus> statuses);
}
