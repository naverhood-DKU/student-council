-- V10: 지출 절차 AI 안내(작업 3).
-- expense_review 에는 V1 에서 missing_facts(JSON), guidance(TEXT), reviewed_by 가 이미 있다. 여기서는 그 둘을 쓰고
--   - ai_result: 서버 검증을 통과한 AI 안내 중 충돌 가능성·걸러낸 개수·결과 종류. 조항 원문은 넣지 않는다.
--   - reviewed_at: 사람이 안내와 조항 원문을 확인했다고 표시한 시각
-- 을 더한다. 필요 승인·필요 증빙은 체크 상태를 저장해야 하므로 expense_checklist_item 에 한 줄씩 둔다.

ALTER TABLE expense_review
    ADD COLUMN ai_result   JSON        NULL COMMENT '검증을 통과한 AI 안내: outcome, conflicts, droppedCount, droppedNotes, generatedAt' AFTER guidance,
    ADD COLUMN reviewed_at DATETIME(6) NULL AFTER reviewed_by;

CREATE TABLE expense_checklist_item (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    review_id   BIGINT        NOT NULL,
    seq         INT           NOT NULL COMMENT '화면 순서(승인 먼저, 증빙 다음)',
    kind        VARCHAR(20)   NOT NULL COMMENT 'APPROVAL(필요한 승인) / EVIDENCE(필요한 증빙)',
    text        VARCHAR(500)  NOT NULL COMMENT 'AI 가 쓴 항목 문구. 서버가 인용 구절을 확인한 것만 들어온다',
    clause_ids  JSON          NOT NULL COMMENT '근거 조항 id. 후보 조항 안에 있는 것만',
    quote       VARCHAR(1000) NOT NULL COMMENT '근거 조항 원문에서 확인된 인용 구절',
    checked     BOOLEAN       NOT NULL DEFAULT FALSE,
    checked_by  BIGINT        NULL,
    checked_at  DATETIME(6)   NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_expense_checklist_review (review_id, seq),
    CONSTRAINT fk_expense_checklist_review FOREIGN KEY (review_id) REFERENCES expense_review (id),
    CONSTRAINT fk_expense_checklist_checked_by FOREIGN KEY (checked_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
