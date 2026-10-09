-- V3: 업무가 AI 후보에서 왔는지, 회의 책임자가 직접 추가했는지 구분한다.
-- 직접 추가한 업무는 근거 발언이 없을 수 있다(사람이 만든 것이라 "근거 확인 필요" 대상이 아님).
ALTER TABLE task
    ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'AI' COMMENT 'AI(분석 후보) / MANUAL(직접 추가)';
