-- V16: AI가 업무 내용을 보고 고른 담당 부서 표시.
-- 회의에서 담당을 말하지 않았을 때 AI가 등록된 부서 중 하나를 골라 담당으로 넣는다(회장이 승인 전에 확인·변경).
-- 회장이 다른 부서로 바꾸면 표시와 이유를 지운다.
ALTER TABLE task
    ADD COLUMN department_by_ai BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'AI가 업무 내용으로 고른 담당(회의에서 말한 담당이 아님)' AFTER department_id,
    ADD COLUMN department_reason VARCHAR(300) NULL COMMENT 'AI가 그 부서를 고른 이유' AFTER department_by_ai;
