package kr.dku.councilflow.join;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, Long> {

    Optional<JoinRequest> findByUserIdAndOrganizationId(Long userId, Long orgId);

    /** 그룹에 들어온 처리 전 신청(먼저 신청한 순) */
    @Query("""
            select r from JoinRequest r
              join fetch r.user
             where r.organization.id = :orgId and r.status = kr.dku.councilflow.join.JoinRequestStatus.PENDING
             order by r.updatedAt, r.id
            """)
    List<JoinRequest> findPendingByOrgId(Long orgId);

    /** 내 신청 중 승인되지 않은 것(승인된 것은 소속으로 보인다) */
    @Query("""
            select r from JoinRequest r
              join fetch r.organization
             where r.user.id = :userId and r.status <> kr.dku.councilflow.join.JoinRequestStatus.APPROVED
             order by r.updatedAt desc, r.id desc
            """)
    List<JoinRequest> findOpenByUserId(Long userId);
}
