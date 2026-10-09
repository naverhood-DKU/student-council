package kr.dku.councilflow.org;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByOrganizationIdOrderById(Long orgId);

    /** 그룹의 지금 쓰는 부서(없앤 부서 제외) */
    List<Department> findByOrganizationIdAndActiveTrueOrderById(Long orgId);

    /** 그 그룹의 지금 쓰는 부서. 다른 그룹 부서나 없앤 부서면 비어 있다. */
    default Optional<Department> findActiveInOrg(long departmentId, long orgId) {
        return findById(departmentId).filter(d -> d.isActive() && d.getOrganization().getId() == orgId);
    }
}
