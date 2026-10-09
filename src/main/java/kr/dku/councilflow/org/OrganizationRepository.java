package kr.dku.councilflow.org;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    List<Organization> findByActiveTrueOrderById();

    Optional<Organization> findByJoinCode(String joinCode);
}
