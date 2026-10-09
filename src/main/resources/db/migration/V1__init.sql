-- V1: CLAUDE.md 도메인 모델의 모든 테이블.
-- 시각은 모두 UTC, DATETIME(6). 상태·종류 값은 VARCHAR에 enum 이름을 그대로 저장한다.
-- 변경 가능한 테이블에는 version(낙관적 잠금), created_at, updated_at 이 있다.

CREATE TABLE organization (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL COMMENT 'STUDENT_UNION / COLLEGE / DEPARTMENT',
    parent_id   BIGINT       NULL,
    term        VARCHAR(20)  NOT NULL COMMENT '임기. 예: 2026',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES organization (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE department (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    org_id         BIGINT       NOT NULL,
    name           VARCHAR(50)  NOT NULL,
    role_keywords  VARCHAR(500) NULL COMMENT '쉼표로 구분한 역할 키워드. 예: 예산,회계,정산',
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_org_name (org_id, name),
    CONSTRAINT fk_department_org FOREIGN KEY (org_id) REFERENCES organization (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE app_user (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    name           VARCHAR(50)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE membership (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    org_id         BIGINT       NOT NULL,
    role           VARCHAR(30)  NOT NULL COMMENT 'ORG_ADMIN / MEETING_CHAIR / DEPT_MEMBER / FINANCE_REVIEWER',
    department_id  BIGINT       NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    term           VARCHAR(20)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership (user_id, org_id, role, term),
    KEY idx_membership_user_org (user_id, org_id, active),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_membership_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_membership_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE meeting (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    org_id              BIGINT       NOT NULL,
    title               VARCHAR(200) NOT NULL,
    held_at             DATETIME(6)  NOT NULL,
    source_type         VARCHAR(10)  NOT NULL COMMENT 'TEXT / AUDIO',
    file_key            VARCHAR(500) NULL COMMENT 'Object Storage 키 (녹음일 때)',
    transcript_version  INT          NOT NULL DEFAULT 1,
    status              VARCHAR(30)  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_meeting_org_held (org_id, held_at),
    CONSTRAINT fk_meeting_org FOREIGN KEY (org_id) REFERENCES organization (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 발언 단위. 전사 버전마다 새 행을 쌓으므로 수정하지 않는다(created_at 만 있음).
CREATE TABLE segment (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    meeting_id          BIGINT       NOT NULL,
    transcript_version  INT          NOT NULL,
    seq                 INT          NOT NULL,
    speaker_label       VARCHAR(50)  NULL COMMENT '화자1, 화자2 … 실명과 자동 연결하지 않는다',
    text                TEXT         NOT NULL,
    start_ms            INT          NULL,
    end_ms              INT          NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_segment_seq (meeting_id, transcript_version, seq),
    CONSTRAINT fk_segment_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE decision (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    meeting_id          BIGINT       NOT NULL,
    agenda              VARCHAR(300) NOT NULL,
    summary             TEXT         NOT NULL,
    status              VARCHAR(20)  NOT NULL COMMENT 'PROPOSED / APPROVED / ON_HOLD / CHANGED / CANCELLED',
    source_segment_ids  JSON         NOT NULL,
    approved_by         BIGINT       NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_decision_meeting (meeting_id, status),
    CONSTRAINT fk_decision_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id),
    CONSTRAINT fk_decision_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE task (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    decision_id              BIGINT       NOT NULL,
    title                    VARCHAR(300) NOT NULL,
    department_id            BIGINT       NULL COMMENT '회의에서 명시된 담당. 모르면 NULL',
    assignee_user_id         BIGINT       NULL,
    due_date                 DATE         NULL COMMENT '회의에서 명시된 기한. 모르면 NULL',
    status                   VARCHAR(20)  NOT NULL COMMENT 'UNASSIGNED / ASSIGNED / IN_PROGRESS / SUBMITTED / DONE',
    suggested_department_id  BIGINT       NULL COMMENT '부서 역할 기반 추천(확정 아님)',
    suggested_due_date       DATE         NULL COMMENT '추천 기한(확정 아님)',
    source_segment_ids       JSON         NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task_decision (decision_id),
    KEY idx_task_department_status (department_id, status),
    CONSTRAINT fk_task_decision FOREIGN KEY (decision_id) REFERENCES decision (id),
    CONSTRAINT fk_task_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (assignee_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_task_suggested_department FOREIGN KEY (suggested_department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE delivery (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    decision_id   BIGINT       NOT NULL,
    from_org_id   BIGINT       NOT NULL,
    to_org_id     BIGINT       NOT NULL,
    request_type  VARCHAR(20)  NOT NULL COMMENT 'INFORM / COOPERATE / RESULT_NOTICE',
    status        VARCHAR(30)  NOT NULL,
    due_date      DATE         NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 같은 결정을 같은 조직에 두 번 보내지 않는다.
    UNIQUE KEY uk_delivery_decision_to_org (decision_id, to_org_id),
    KEY idx_delivery_to_org_status (to_org_id, status),
    KEY idx_delivery_from_org (from_org_id),
    CONSTRAINT fk_delivery_decision FOREIGN KEY (decision_id) REFERENCES decision (id),
    CONSTRAINT fk_delivery_from_org FOREIGN KEY (from_org_id) REFERENCES organization (id),
    CONSTRAINT fk_delivery_to_org FOREIGN KEY (to_org_id) REFERENCES organization (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE submission (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_id    BIGINT        NOT NULL,
    result_link    VARCHAR(1000) NULL,
    description    TEXT          NULL,
    submitted_by   BIGINT        NOT NULL,
    review_status  VARCHAR(20)   NOT NULL,
    reviewed_by    BIGINT        NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_submission_delivery (delivery_id),
    CONSTRAINT fk_submission_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    CONSTRAINT fk_submission_submitted_by FOREIGN KEY (submitted_by) REFERENCES app_user (id),
    CONSTRAINT fk_submission_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE rule_document (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    title              VARCHAR(200) NOT NULL COMMENT '예: 회칙, 재정 세칙',
    issuer             VARCHAR(100) NOT NULL COMMENT '발행 주체',
    applies_to_org_id  BIGINT       NOT NULL,
    effective_date     DATE         NOT NULL COMMENT '시행일',
    doc_version        VARCHAR(30)  NOT NULL COMMENT '규정 자체의 버전(개정 차수)',
    file_key           VARCHAR(500) NULL COMMENT '원본 파일 Object Storage 키',
    reviewed_by        BIGINT       NULL COMMENT '조항 검수 확정자. NULL이면 아직 사용 불가',
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_rule_document_org (applies_to_org_id, effective_date),
    CONSTRAINT fk_rule_document_org FOREIGN KEY (applies_to_org_id) REFERENCES organization (id),
    CONSTRAINT fk_rule_document_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE clause (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT       NOT NULL,
    number      VARCHAR(20)  NOT NULL COMMENT '예: 제12조',
    title       VARCHAR(200) NULL,
    text        TEXT         NOT NULL COMMENT '검수된 조항 원문. 화면에는 이 값을 그대로 보여 준다',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_clause_doc (doc_id),
    FULLTEXT KEY ft_clause_text (text) WITH PARSER ngram,
    CONSTRAINT fk_clause_doc FOREIGN KEY (doc_id) REFERENCES rule_document (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE expense_review (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    org_id         BIGINT       NOT NULL,
    item           VARCHAR(200) NOT NULL,
    amount         BIGINT       NULL COMMENT '원 단위',
    fund_source    VARCHAR(100) NULL COMMENT '재원',
    planned_date   DATE         NULL COMMENT '집행 예정일',
    status         VARCHAR(30)  NOT NULL COMMENT 'PROCEDURE_FOUND / NEEDS_CHECK / POSSIBLE_CONFLICT / REVIEWED',
    clause_ids     JSON         NULL,
    missing_facts  JSON         NULL,
    guidance       TEXT         NULL,
    reviewed_by    BIGINT       NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_expense_review_org (org_id, status),
    CONSTRAINT fk_expense_review_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_expense_review_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE job (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    type          VARCHAR(20)  NOT NULL COMMENT 'TRANSCRIBE / ANALYZE / EXPENSE',
    target_id     BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL COMMENT 'QUEUED / RUNNING / SUCCEEDED / FAILED / FAILED_UNKNOWN',
    attempts      INT          NOT NULL DEFAULT 0,
    locked_until  DATETIME(6)  NULL COMMENT 'Worker 임대 만료 시각. 지나면 다른 Worker가 다시 가져간다',
    last_error    VARCHAR(500) NULL COMMENT '오류 종류만. 회의 원문을 넣지 않는다',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_job_poll (status, locked_until),
    KEY idx_job_target (type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 감사 기록. 추가만 하고 고치지 않는다.
CREATE TABLE audit_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    actor_user_id  BIGINT       NULL COMMENT 'NULL이면 시스템(Worker)',
    org_id         BIGINT       NULL,
    action         VARCHAR(50)  NOT NULL,
    target_type    VARCHAR(30)  NOT NULL,
    target_id      BIGINT       NOT NULL,
    before_json    JSON         NULL,
    after_json     JSON         NULL,
    at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_target (target_type, target_id),
    KEY idx_audit_org_at (org_id, at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_audit_org FOREIGN KEY (org_id) REFERENCES organization (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
