-- V13: 공통 규정과 회의 업무 카드의 규정 자동 확인.
--
-- 1) 학교 규정(감사규정·총대의원회 회칙·학생회칙)은 그룹마다 올리는 것이 아니라 서비스가 시드로 넣어 모든 그룹이 쓴다.
--    그래서 rule_document.applies_to_org_id 가 NULL 일 수 있다(NULL = 모든 그룹이 쓰는 공통 규정).
--    그룹이 직접 올린 규정(applies_to_org_id 가 있는 것)은 그대로 그 그룹에서만 찾는다.
-- 2) 회의 분석이 끝나면 돈과 관련된 업무 카드에 대해 서버가 규정 후보 조항을 찾고 AI 안내(EXPENSE 작업)를 만든다.
--    그 지출 검토가 어느 업무 카드의 것인지(task_id)와, 사람이 요청한 건인지 자동으로 만든 건인지(origin)를 남긴다.
--    task 행은 회의를 다시 분석하면 지워지므로 외래키를 걸지 않고, 지울 때 서비스가 같이 정리한다.

ALTER TABLE rule_document
    MODIFY COLUMN applies_to_org_id BIGINT NULL COMMENT '등록해 쓰는 그룹. NULL 이면 서비스가 넣은, 모든 그룹이 쓰는 공통 규정';

ALTER TABLE expense_review
    ADD COLUMN task_id BIGINT NULL COMMENT '회의 분석 뒤 자동으로 확인한 업무 카드(task.id). 사람이 요청한 건은 NULL' AFTER decision_id,
    ADD COLUMN origin  VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL(사람이 요청) / AUTO(회의 업무 카드 자동 확인)' AFTER status,
    ADD KEY idx_expense_review_task (task_id);
