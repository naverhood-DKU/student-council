package kr.dku.councilflow.org;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findByOrganizationIdOrderBySortOrderAscIdAsc(Long orgId);
}
