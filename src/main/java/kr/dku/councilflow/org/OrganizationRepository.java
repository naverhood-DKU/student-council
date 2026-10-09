package kr.dku.councilflow.org;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 직속 하위 조직(활성만) */
    List<Organization> findByParentIdAndActiveTrueOrderById(Long parentId);
}
