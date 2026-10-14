package kr.dku.councilflow.rule;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClauseRepository extends JpaRepository<Clause, Long> {

    List<Clause> findByDocIdOrderBySeq(Long docId);

    List<Clause> findByDocIdAndIdIn(Long docId, Collection<Long> ids);

    long countByDocId(Long docId);

    void deleteByDocId(Long docId);

    long countByDocIdAndReviewStatusAndKindIn(Long docId, ClauseReviewStatus status,
                                              Collection<RuleTextSplitter.Kind> kinds);

    long countByDocIdAndKind(Long docId, RuleTextSplitter.Kind kind);

    /**
     * 지출 검토의 후보 조항 찾기. 아래 조건을 모두 만족하는 조항만 FULLTEXT(ngram) 관련도 순으로 돌려준다.
     * - 그 그룹이 등록한 규정이거나 모든 그룹이 쓰는 공통 규정(applies_to_org_id 가 NULL)이고,
     *   확정(CONFIRMED)했으며 기준일(asOf)에 이미 시행 중이다
     * - 조·보칙·부칙이고 사람이 검수 완료(REVIEWED)했다
     * 검수 전·확정 전 조항은 어떤 경우에도 나오지 않는다(CLAUDE.md 원칙 4).
     */
    @Query(value = """
            SELECT c.* FROM clause c
            JOIN rule_document d ON d.id = c.doc_id
            WHERE (d.applies_to_org_id = :orgId OR d.applies_to_org_id IS NULL)
              AND d.status = 'CONFIRMED'
              AND d.effective_date <= :asOf
              AND c.kind IN ('ARTICLE', 'EXTRA', 'SUPPLEMENTARY')
              AND c.review_status = 'REVIEWED'
              AND MATCH(c.text) AGAINST (:query IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(c.text) AGAINST (:query IN NATURAL LANGUAGE MODE) DESC, c.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Clause> searchConfirmed(@Param("orgId") Long orgId, @Param("query") String query,
                                 @Param("asOf") LocalDate asOf, @Param("limit") int limit);

    /** 새 조각을 seq 자리에 끼우려고, 그 뒤 조각들의 순서를 하나씩 민다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Clause c set c.seq = c.seq + 1 where c.docId = :docId and c.seq >= :fromSeq")
    int shiftFrom(@Param("docId") Long docId, @Param("fromSeq") int fromSeq);
}
