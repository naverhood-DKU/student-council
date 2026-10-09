-- V6: 가입 신청과 업무 체크리스트.

-- 1) 그룹 가입 코드. 부장·부원이 회원가입할 때 이 코드를 넣으면 그 그룹 회장에게 가입 신청이 간다.
--    예전에 만든 그룹은 코드가 없고(NULL), 서비스 관리자가 정해 줄 때까지 가입 신청을 받지 않는다.
ALTER TABLE organization
    ADD COLUMN join_code VARCHAR(30) NULL COMMENT '가입 코드(영문 소문자·숫자·.-_)' AFTER name,
    ADD UNIQUE KEY uk_organization_join_code (join_code);

-- 2) 가입 신청. 회장(owner)이 역할(부장·부원)과 부서를 정해 승인하거나 거절한다.
--    한 사람·한 그룹에 한 줄만 두고, 거절된 뒤 다시 신청하면 같은 줄을 PENDING 으로 되돌린다.
CREATE TABLE join_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    org_id         BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED',
    decided_by     BIGINT       NULL,
    decided_at     DATETIME(6)  NULL,
    reject_reason  VARCHAR(500) NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_join_request_user_org (user_id, org_id),
    KEY idx_join_request_org_status (org_id, status),
    CONSTRAINT fk_join_request_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_join_request_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_join_request_decided_by FOREIGN KEY (decided_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 3) 업무 체크리스트. 회장이나 담당 부서 부장이 업무를 잘게 나눠 부서 사람에게 맡기고, 맡은 사람이 완료를 체크한다.
CREATE TABLE task_item (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    task_id           BIGINT       NOT NULL,
    title             VARCHAR(300) NOT NULL,
    assignee_user_id  BIGINT       NULL,
    done              BOOLEAN      NOT NULL DEFAULT FALSE,
    done_by           BIGINT       NULL,
    done_at           DATETIME(6)  NULL,
    created_by        BIGINT       NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task_item_task (task_id),
    KEY idx_task_item_assignee (assignee_user_id, done),
    CONSTRAINT fk_task_item_task FOREIGN KEY (task_id) REFERENCES task (id),
    CONSTRAINT fk_task_item_assignee FOREIGN KEY (assignee_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_task_item_done_by FOREIGN KEY (done_by) REFERENCES app_user (id),
    CONSTRAINT fk_task_item_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
