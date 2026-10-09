package kr.dku.councilflow.decision;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DecisionRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByMeetingIdOrderById(Long meetingId);

    @Query("""
            select d from Decision d join fetch d.meeting m
             where m.organization.id = :orgId and d.status = :status
             order by m.heldAt desc, d.id
            """)
    List<Decision> findByOrgAndStatus(Long orgId, DecisionStatus status);

    @Query("select count(d) > 0 from Decision d where d.meeting.id = :meetingId and d.status <> :status")
    boolean existsByMeetingIdAndStatusNot(Long meetingId, DecisionStatus status);
}
