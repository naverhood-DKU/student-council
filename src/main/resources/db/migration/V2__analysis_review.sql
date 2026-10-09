-- V2: 회의 분석 결과(AI 후보)를 사람이 검토하는 데 필요한 컬럼.

ALTER TABLE decision
    ADD COLUMN ai_status        VARCHAR(20) NULL COMMENT 'AI가 본 상태: CONFIRMED / ON_HOLD / DISCUSSING (확정 아님)',
    ADD COLUMN evidence_status  VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK / NEEDS_CHECK(근거 확인 필요)',
    ADD COLUMN validation_notes JSON        NULL COMMENT '서버 검증에서 나온 안내 문구 목록',
    ADD COLUMN expense_proposal JSON        NULL COMMENT '지출 언급: { item, amount, fundSource } (단계 4에서 사용)';

ALTER TABLE task
    ADD COLUMN owner_department_text VARCHAR(100) NULL COMMENT '회의록에 나온 담당 표현. 등록 부서와 다를 수 있다',
    ADD COLUMN evidence_status       VARCHAR(20)  NOT NULL DEFAULT 'OK' COMMENT 'OK / NEEDS_CHECK(근거 확인 필요)',
    ADD COLUMN validation_notes      JSON         NULL COMMENT '서버 검증에서 나온 안내 문구 목록';
