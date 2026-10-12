package kr.dku.councilflow.job;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 임대 갱신의 짧은 DB 트랜잭션. 잠금 획득 후 새 SQL 문장의 시각으로 만료를 검사한다. */
@Component
public class JobLeaseStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    JobLeaseStore(JdbcTemplate source, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(3);
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
    }

    boolean renew(long id, long version, Duration duration) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var rows = jdbc.queryForList("""
                    SELECT id FROM job WHERE id=? AND version=? AND status='RUNNING' FOR UPDATE
                    """, id, version);
            if (rows.isEmpty()) return false;
            // UTC_TIMESTAMP is fixed at statement start: do not combine waiting for a lock with this check.
            return jdbc.update("""
                    UPDATE job SET locked_until=TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                     WHERE id=? AND version=? AND status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)
                    """, duration.toNanos() / 1000, id, version) == 1;
        }));
    }
}
