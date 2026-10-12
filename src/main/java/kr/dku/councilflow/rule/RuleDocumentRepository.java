package kr.dku.councilflow.rule;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuleDocumentRepository extends JpaRepository<RuleDocument, Long> {

    List<RuleDocument> findByOrgIdOrderByIdDesc(Long orgId);

    long countByOrgIdAndStatus(Long orgId, RuleDocStatus status);

    /** 검수·확정이 서로 엇갈리지 않도록 문서 줄을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RuleDocument d where d.id = :id")
    Optional<RuleDocument> findByIdForUpdate(@Param("id") Long id);
}
