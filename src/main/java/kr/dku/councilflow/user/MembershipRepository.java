package kr.dku.councilflow.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    /** 사용자의 활성 소속 전체(조직도 활성인 것만). 조직 선택 화면용. */
    @Query("""
            select m from Membership m
              join fetch m.organization o
              left join fetch m.position
              left join fetch m.department
             where m.user.id = :userId and m.active = true and o.active = true
             order by o.id, m.role
            """)
    List<Membership> findActiveByUserId(Long userId);

    /** 그룹의 활성 구성원(구성원 관리 화면) */
    @Query("""
            select m from Membership m
              join fetch m.user
              left join fetch m.position
              left join fetch m.department
             where m.organization.id = :orgId and m.active = true
             order by m.id
            """)
    List<Membership> findActiveByOrgId(Long orgId);

    /** 그 직책을 가진 활성 구성원(직책의 권한 수준을 바꿀 때 같이 바꾼다) */
    @Query("select m from Membership m where m.position.id = :positionId and m.active = true")
    List<Membership> findActiveByPositionId(Long positionId);

    /** 직책을 지울 때, 끊긴 소속에 남은 그 직책 표시를 지운다. */
    @Modifying
    @Query("update Membership m set m.position = null where m.position.id = :positionId and m.active = false")
    int clearPositionOfInactive(Long positionId);

    /** 사용자의 그 그룹·임기 소속 줄(활성 여부 무관). 다시 부여할 때 같은 줄을 살린다. */
    Optional<Membership> findByUserIdAndOrganizationIdAndTerm(Long userId, Long orgId, String term);

    /** 한 조직 안에서의 활성 소속. 인가 판단(OrgAccessGuard)에 쓴다. */
    @Query("""
            select m from Membership m
             where m.user.id = :userId and m.organization.id = :orgId
               and m.active = true and m.organization.active = true
            """)
    List<Membership> findActiveByUserIdAndOrgId(Long userId, Long orgId);
}
