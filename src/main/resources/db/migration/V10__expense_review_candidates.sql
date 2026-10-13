-- V9: 지출 검토 요청과 후보 조항 검색(단계 3 앞부분).
-- expense_review 는 V1 에서 만들었다. 요청한 사람·근거가 된 결정·실제 쓴 검색어를 남기는 열을 더한다.
-- clause_ids 에는 FULLTEXT 검색으로 찾은 후보 조항 id 를 관련도 순서대로 넣는다. 화면의 조항 원문은 항상 clause.text 에서 읽는다.

ALTER TABLE expense_review
    MODIFY COLUMN status VARCHAR(30) NOT NULL
        COMMENT 'CANDIDATES_FOUND(후보 조항 찾음, 안내 전) / NEEDS_CHECK / PROCEDURE_FOUND / POSSIBLE_CONFLICT / REVIEWED. 문제없음 상태는 없다',
    ADD COLUMN decision_id  BIGINT        NULL COMMENT '지출을 언급한 승인 결정(없으면 직접 입력한 요청)' AFTER org_id,
    ADD COLUMN note         VARCHAR(1000) NULL COMMENT '요청자가 덧붙인 설명' AFTER planned_date,
    ADD COLUMN search_text  VARCHAR(600)  NULL COMMENT '후보 조항을 찾을 때 실제로 쓴 검색어' AFTER note,
    ADD COLUMN requested_by BIGINT        NULL,
    ADD KEY idx_expense_review_decision (decision_id),
    ADD CONSTRAINT fk_expense_review_decision FOREIGN KEY (decision_id) REFERENCES decision (id),
    ADD CONSTRAINT fk_expense_review_requested_by FOREIGN KEY (requested_by) REFERENCES app_user (id);
