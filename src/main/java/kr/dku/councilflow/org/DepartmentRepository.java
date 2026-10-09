package kr.dku.councilflow.org;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByOrganizationIdOrderById(Long orgId);
}
