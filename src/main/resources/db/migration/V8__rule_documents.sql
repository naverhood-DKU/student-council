-- V8: 규정 등록·검수를 위해 V1 의 rule_document / clause 를 넓힌다.
-- 두 테이블은 V1 에서 만들었고 아직 쓰는 코드가 없어 비어 있다.
-- 업로드한 PDF 는 저장하지 않고(file_key 는 쓰지 않는다), 글자 추출본(source_text)과 PDF 의 SHA-256 만 남긴다.
-- 조항 본문(text)은 PDF 에서 뽑은 글자 그대로다. 사람이 고친 경우 edited = TRUE 이고 고친 내용은 audit_log 에 남는다.
-- text 의 FULLTEXT ngram 인덱스(ft_clause_text)는 V1 것을 그대로 쓴다. 조 제목이 본문 첫 줄에 들어 있어 text 만 찾으면 된다.

ALTER TABLE rule_document
    MODIFY COLUMN issuer         VARCHAR(200) NOT NULL COMMENT '발행 주체',
    MODIFY COLUMN effective_date DATE         NULL COMMENT '시행일. 확정할 때 필수',
    MODIFY COLUMN doc_version    VARCHAR(100) NULL COMMENT '규정 자체의 버전(개정 차수). 확정할 때 필수',
    ADD COLUMN source_type   VARCHAR(20)  NOT NULL DEFAULT 'REGULATION' COMMENT 'REGULATION(회칙·세칙·규정). 안내 자료는 아직 지원하지 않음' AFTER title,
    ADD COLUMN applies_to    VARCHAR(300) NULL COMMENT '적용 대상 설명',
    ADD COLUMN notes         TEXT         NULL COMMENT '등록자 메모(표지·본문 날짜가 다르다는 등)',
    ADD COLUMN file_name     VARCHAR(255) NULL,
    ADD COLUMN source_sha256 VARCHAR(64)  NULL,
    ADD COLUMN source_text   LONGTEXT     NOT NULL COMMENT '쪽번호 줄만 걷어낸 추출 글자. 분리 결과를 이어 붙이면 이것과 같아야 한다',
    ADD COLUMN roundtrip_ok  BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '분리 직후 이어 붙인 결과가 source_text 와 같았는가',
    ADD COLUMN status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / CONFIRMED / RETIRED',
    ADD COLUMN reviewed_at   DATETIME(6)  NULL,
    ADD KEY idx_rule_document_status (applies_to_org_id, status);

-- clause.number(예: 제12조)는 장·절·부칙 이름도 담도록 label 로 바꾸고, 조 번호 숫자는 article_no 에 따로 둔다.
ALTER TABLE clause
    CHANGE COLUMN number label VARCHAR(60) NOT NULL COMMENT '화면 표시용. 예: 제5조, 제95조의2, 부칙',
    MODIFY COLUMN title VARCHAR(300) NULL,
    MODIFY COLUMN text  MEDIUMTEXT NOT NULL COMMENT '추출한 조항 원문. 확정된 규정의 이 값을 화면에 그대로 보여 준다';

ALTER TABLE clause
    ADD COLUMN seq           INT          NOT NULL DEFAULT 0 COMMENT '문서 안 순서(1부터)' AFTER doc_id,
    ADD COLUMN kind          VARCHAR(20)  NOT NULL DEFAULT 'ARTICLE' COMMENT 'PREAMBLE / CHAPTER / SECTION / ARTICLE / EXTRA / SUPPLEMENTARY' AFTER seq,
    ADD COLUMN article_no    INT          NULL COMMENT '조 번호(ARTICLE)',
    ADD COLUMN branch_number INT          NULL COMMENT '가지 번호(제n조의m 의 m, 없으면 0)',
    ADD COLUMN chapter       VARCHAR(200) NULL,
    ADD COLUMN section_name  VARCHAR(200) NULL,
    ADD COLUMN review_status VARCHAR(20)  NOT NULL DEFAULT 'UNREVIEWED' COMMENT 'UNREVIEWED / REVIEWED',
    ADD COLUMN edited        BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN flags         VARCHAR(1000) NULL COMMENT '분리 때 붙인 주의 표시(줄바꿈으로 구분)',
    ADD KEY idx_clause_doc_seq (doc_id, seq);
