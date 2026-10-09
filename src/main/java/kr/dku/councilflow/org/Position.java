package kr.dku.councilflow.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.user.MembershipRole;

/**
 * 그룹의 직책(부회장·총무·부장·부원 …). 회장이 이름과 권한 수준을 정한다.
 * 권한 수준은 EDITOR(부장급: 자기 부서 업무 진행·체크리스트) 또는 VIEWER(부원급: 보기·맡은 체크리스트)뿐이다.
 * 회장(OWNER)은 직책이 아니라 서비스 관리자가 주는 자리다.
 */
@Entity
@Table(name = "org_position")
public class Position extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 30)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipRole level;

    @Column(nullable = false)
    private int sortOrder;

    protected Position() {
    }

    public Position(Organization organization, String name, MembershipRole level, int sortOrder) {
        this.organization = organization;
        this.name = name;
        this.level = level;
        this.sortOrder = sortOrder;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeLevel(MembershipRole level) {
        this.level = level;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public MembershipRole getLevel() {
        return level;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
