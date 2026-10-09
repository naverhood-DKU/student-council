package kr.dku.councilflow.meeting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    List<Meeting> findByOrganizationIdOrderByHeldAtDescIdDesc(Long orgId);
}
