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

/**
 * 사용자가 어느 조직에서 어떤 역할인지. 한 사람이 같은 조직에서 역할을 여러 개 가질 수 있다(행이 여러 개).
 * 임기가 끝나면 active=false 로 바꿔 권한을 회수한다.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, length = 20)
    private String term;

    protected Membership() {
    }

    public Membership(AppUser user, Organization organization, MembershipRole role, Department department, String term) {
        this.user = user;
        this.organization = organization;
        this.role = role;
        this.department = department;
        this.term = term;
    }

    public void deactivate() {
        this.active = false;
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
