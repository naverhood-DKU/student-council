-- V5: 권한 개편. 총학·단과대·학과를 권한상 구분하지 않고, 그룹마다 owner(회장)·editor(부장)·viewer(부원)를 둔다.
-- 서비스 관리자는 그룹 소속이 아니라 계정 속성이다.

-- 1) 예전 고유 제약(user, org, role, term)을 먼저 푼다.
--    한 사람이 ORG_ADMIN·MEETING_CHAIR 를 둘 다 갖고 있으면 둘 다 OWNER 로 바뀌면서 예전 제약에 걸리기 때문이다.
--    (user_id 외래 키는 idx_membership_user_org 인덱스가 받쳐 준다)
ALTER TABLE membership DROP INDEX uk_membership;

-- 2) 예전 역할을 새 역할로 바꾼다.
--    조직 관리자·회의 책임자 → OWNER, 부서원·재정 담당 → EDITOR
UPDATE membership SET role = 'OWNER' WHERE role IN ('ORG_ADMIN', 'MEETING_CHAIR');
UPDATE membership SET role = 'EDITOR' WHERE role IN ('DEPT_MEMBER', 'FINANCE_REVIEWER');

-- 3) 한 그룹·한 임기에 한 사람은 역할 하나만 갖는다. 겹치는 줄은 하나만 남긴다
--    (활성 줄 우선, 그다음 높은 역할 OWNER > EDITOR > VIEWER, 그다음 먼저 만든 줄).
DELETE m1 FROM membership m1
  JOIN membership m2
    ON m1.user_id = m2.user_id AND m1.org_id = m2.org_id AND m1.term = m2.term AND m1.id <> m2.id
 WHERE m2.active > m1.active
    OR (m2.active = m1.active AND FIELD(m2.role, 'OWNER', 'EDITOR', 'VIEWER') < FIELD(m1.role, 'OWNER', 'EDITOR', 'VIEWER'))
    OR (m2.active = m1.active AND m2.role = m1.role AND m2.id < m1.id);

ALTER TABLE membership
    ADD UNIQUE KEY uk_membership_user_org_term (user_id, org_id, term),
    MODIFY COLUMN role VARCHAR(30) NOT NULL COMMENT 'OWNER(회장) / EDITOR(부장) / VIEWER(부원)';

-- 4) 서비스 관리자: 그룹을 만들고 owner를 부여·회수한다. 모든 그룹의 내용을 읽기 전용으로 볼 수 있다.
ALTER TABLE app_user
    ADD COLUMN service_admin BOOLEAN NOT NULL DEFAULT FALSE COMMENT '서비스 관리자';
