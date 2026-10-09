package kr.dku.councilflow.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import kr.dku.councilflow.common.BaseEntity;

/** 조직 안의 부서(기획부·홍보부·재정부 …). role_keywords 는 AI 담당 부서 추천의 근거가 된다. */
@Entity
@Table(name = "department")
public class Department extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 50)
    private String name;

    /** 쉼표로 구분한 역할 키워드. 예: "예산,회계,정산" */
    @Column(length = 500)
    private String roleKeywords;

    /** 없앤 부서는 false. 지난 업무·전달 기록이 가리키므로 지우지 않고 목록·선택에서만 숨긴다. */
    @Column(nullable = false)
    private boolean active = true;

    protected Department() {
    }

    public Department(Organization organization, String name, String roleKeywords) {
        this.organization = organization;
        this.name = name;
        this.roleKeywords = roleKeywords;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeRoleKeywords(String roleKeywords) {
        this.roleKeywords = roleKeywords;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 없앴던 부서를 같은 이름으로 다시 만들 때 */
    public void reactivate(String roleKeywords) {
        this.active = true;
        this.roleKeywords = roleKeywords;
    }

    public boolean isActive() {
        return active;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getRoleKeywords() {
        return roleKeywords;
    }
}
