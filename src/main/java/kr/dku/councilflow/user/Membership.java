package kr.dku.councilflow.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.Position;

/**
 * 사용자가 어느 그룹에서 어떤 역할인지. 한 그룹·한 임기에 한 사람은 역할 하나(DB 고유 제약).
 * 한 사람이 여러 그룹에 속할 수는 있다. 임기가 끝나거나 권한을 회수하면 active=false 로 바꾼다.
 */
@Entity
@Table(name = "membership")
public class Membership extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MembershipRole role;

    /** 직책. 권한(role)은 직책의 권한 수준을 따른다. 회장(OWNER)은 직책이 없다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id")
    private Position position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, length = 20)
    private String term;

    protected Membership() {
    }

    /** 회장(OWNER) 소속. 직책 없음. */
    public static Membership owner(AppUser user, Organization organization, String term) {
        return new Membership(user, organization, MembershipRole.OWNER, null, null, term);
    }

    /** 직책을 가진 소속. 권한은 직책의 권한 수준을 따른다. */
    public static Membership withPosition(AppUser user, Organization organization, Position position,
                                          Department department, String term) {
        return new Membership(user, organization, position.getLevel(), position, department, term);
    }

    private Membership(AppUser user, Organization organization, MembershipRole role, Position position,
                       Department department, String term) {
        this.user = user;
        this.organization = organization;
        this.role = role;
        this.position = position;
        this.department = department;
        this.term = term;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 끊었던 소속을 같은 임기에 다시 부여할 때(한 그룹·한 임기에 줄은 하나뿐). position 이 null 이면 회장. */
    public void reactivate(Position position, Department department) {
        this.active = true;
        this.role = position == null ? MembershipRole.OWNER : position.getLevel();
        this.position = position;
        this.department = department;
    }

    /** 직책 바꾸기. 권한도 그 직책의 권한 수준으로 바뀐다. */
    public void changePosition(Position position) {
        this.position = position;
        this.role = position.getLevel();
    }

    /** 직책의 권한 수준이 바뀌었을 때 따라간다. */
    public void followPositionLevel() {
        if (position != null) {
            this.role = position.getLevel();
        }
    }

    public void assignDepartment(Department department) {
        this.department = department;
    }

    public AppUser getUser() {
        return user;
    }

    public Organization getOrganization() {
        return organization;
    }

    public MembershipRole getRole() {
        return role;
    }

    public Position getPosition() {
        return position;
    }

    public Department getDepartment() {
        return department;
    }

    public boolean isActive() {
        return active;
    }

    public String getTerm() {
        return term;
    }
}
