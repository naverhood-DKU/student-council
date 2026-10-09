package kr.dku.councilflow.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 누가 언제 무엇을 바꿨는지. 추가만 하고 고치지 않는다. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorUserId;

    private Long orgId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(nullable = false, length = 30)
    private String targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(columnDefinition = "json")
    private String beforeJson;

    @Column(columnDefinition = "json")
    private String afterJson;

    @Column(nullable = false)
    private Instant at;

    protected AuditLog() {
    }

    AuditLog(Long actorUserId, Long orgId, String action, String targetType, Long targetId,
             String beforeJson, String afterJson) {
        this.actorUserId = actorUserId;
        this.orgId = orgId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.at = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public Instant getAt() {
        return at;
    }
}
