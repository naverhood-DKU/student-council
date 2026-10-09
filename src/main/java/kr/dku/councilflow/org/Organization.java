package kr.dku.councilflow.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.regex.Pattern;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;

/**
 * 학생회 그룹. 종류(총학·단과대·학과)와 parent 는 화면 표시용이고 권한에는 쓰지 않는다.
 * joinCode 는 회원가입할 때 넣는 가입 코드다. 없으면 가입 신청을 받지 않는다.
 */
@Entity
@Table(name = "organization")
public class Organization extends BaseEntity {

    /** 가입 코드: 영문 소문자·숫자로 시작하고 영문 소문자·숫자·. - _ 로 2~30자 */
    private static final Pattern JOIN_CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{1,29}");

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30, unique = true)
    private String joinCode;

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

    /** 가입 코드를 정한다. 대소문자는 구분하지 않고 소문자로 저장한다. */
    public void changeJoinCode(String code) {
        this.joinCode = normalizeJoinCode(code);
    }

    /** 사용자가 넣은 가입 코드를 저장 형식(소문자)으로 바꾸고, 형식이 틀리면 400. */
    public static String normalizeJoinCode(String code) {
        String normalized = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        if (!JOIN_CODE.matcher(normalized).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "가입 코드는 영문 소문자·숫자·. - _ 로 2~30자(첫 글자는 영문·숫자)로 정해 주세요.");
        }
        return normalized;
    }

    public String getName() {
        return name;
    }

    public String getJoinCode() {
        return joinCode;
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
