-- V4: 조직 간 전달(단계 2)에 필요한 컬럼.

ALTER TABLE delivery
    ADD COLUMN message                TEXT        NULL COMMENT '발신 조직의 요청 사항',
    ADD COLUMN internal_department_id BIGINT      NULL COMMENT '수신 조직 안에서 맡을 부서',
    ADD COLUMN clarification_reason   TEXT        NULL COMMENT '수신 조직이 확인 요청한 사유',
    ADD COLUMN clarification_reply    TEXT        NULL COMMENT '발신 조직의 답변',
    ADD COLUMN closing_reason         TEXT        NULL COMMENT '해당 없음·취소 사유',
    ADD COLUMN previous_due_date      DATE        NULL COMMENT '기한을 바꾸기 전 값(수신함에 "기한 변경됨" 표시)',
    ADD COLUMN due_changed_at         DATETIME(6) NULL,
    ADD COLUMN created_by             BIGINT      NULL,
    ADD CONSTRAINT fk_delivery_internal_department FOREIGN KEY (internal_department_id) REFERENCES department (id),
    ADD CONSTRAINT fk_delivery_created_by FOREIGN KEY (created_by) REFERENCES app_user (id);

-- 제출물은 고치지 않는다. 반려되면 새로 제출한다.
ALTER TABLE submission
    ADD COLUMN review_comment TEXT        NULL COMMENT '확정·반려 의견(반려는 필수)',
    ADD COLUMN reviewed_at    DATETIME(6) NULL;
