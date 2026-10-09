package kr.dku.councilflow.audit;

import kr.dku.councilflow.common.JsonColumns;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사람이 상태를 바꾼 기록을 같은 트랜잭션 안에서 남긴다(변경이 취소되면 기록도 같이 취소). */
@Service
public class AuditService {

    private final AuditLogRepository logs;

    AuditService(AuditLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorUserId, Long orgId, String action, String targetType, Long targetId,
                       Object before, Object after) {
        logs.save(new AuditLog(actorUserId, orgId, action, targetType, targetId,
                JsonColumns.write(before), JsonColumns.write(after)));
    }
}
