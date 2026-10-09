package kr.dku.councilflow.user;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    /** 사용자의 활성 소속 전체(조직도 활성인 것만). 조직 선택 화면용. */
    @Query("""
            select m from Membership m
              join fetch m.organization o
              left join fetch m.department
             where m.user.id = :userId and m.active = true and o.active = true
             order by o.id, m.role
            """)
    List<Membership> findActiveByUserId(Long userId);

    /** 한 조직 안에서의 활성 소속. 인가 판단(OrgAccessGuard)에 쓴다. */
    @Query("""
            select m from Membership m
             where m.user.id = :userId and m.organization.id = :orgId
               and m.active = true and m.organization.active = true
            """)
    List<Membership> findActiveByUserIdAndOrgId(Long userId, Long orgId);
}
