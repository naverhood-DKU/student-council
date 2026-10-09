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

/** 학생회 조직. 총학생회 → 단과대 → 학과 순으로 parent 를 가진다. */
@Entity
@Table(name = "organization")
public class Organization extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrgType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Organization parent;

    @Column(nullable = false, length = 20)
    private String term;

    @Column(nullable = false)
    private boolean active = true;

    protected Organization() {
    }

    public Organization(String name, OrgType type, Organization parent, String term) {
        this.name = name;
        this.type = type;
        this.parent = parent;
        this.term = term;
    }

    public String getName() {
        return name;
    }

    public OrgType getType() {
        return type;
    }

    public Organization getParent() {
        return parent;
    }

    public String getTerm() {
        return term;
    }

    public boolean isActive() {
        return active;
    }
}
