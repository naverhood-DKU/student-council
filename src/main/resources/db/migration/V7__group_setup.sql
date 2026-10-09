-- V7: 회장이 그룹의 부서와 직책을 정한다.

-- 1) 부서 없애기는 지우지 않고 숨긴다(지난 업무·전달 기록이 그 부서를 가리키므로).
ALTER TABLE department
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '없앤 부서는 FALSE(목록·선택에서 숨김)' AFTER role_keywords;

-- 2) 직책: 그룹마다 이름(부회장·총무·부장·부원 …)과 권한 수준(EDITOR=부장급, VIEWER=부원급)을 정한다.
--    구성원의 권한(membership.role)은 직책의 권한 수준을 따른다. 회장(OWNER)은 직책 없이 서비스 관리자가 준다.
CREATE TABLE org_position (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    org_id      BIGINT       NOT NULL,
    name        VARCHAR(30)  NOT NULL,
    level       VARCHAR(20)  NOT NULL COMMENT 'EDITOR(부장급) / VIEWER(부원급)',
    sort_order  INT          NOT NULL DEFAULT 0,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_org_position_org_name (org_id, name),
    CONSTRAINT fk_org_position_org FOREIGN KEY (org_id) REFERENCES organization (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 기존 그룹마다 기본 직책 두 개(부장·부원)를 만든다.
INSERT INTO org_position (org_id, name, level, sort_order, version, created_at, updated_at)
SELECT id, '부장', 'EDITOR', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM organization;
INSERT INTO org_position (org_id, name, level, sort_order, version, created_at, updated_at)
SELECT id, '부원', 'VIEWER', 2, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM organization;

-- 3) 구성원의 직책. 기존 부장·부원 소속에는 같은 권한 수준의 기본 직책을 붙인다.
ALTER TABLE membership
    ADD COLUMN position_id BIGINT NULL COMMENT '직책(회장은 NULL)' AFTER role,
    ADD CONSTRAINT fk_membership_position FOREIGN KEY (position_id) REFERENCES org_position (id);

UPDATE membership m
  JOIN org_position p ON p.org_id = m.org_id AND p.level = m.role
   SET m.position_id = p.id
 WHERE m.role IN ('EDITOR', 'VIEWER');
