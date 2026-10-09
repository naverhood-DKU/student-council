package kr.dku.councilflow.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Locale;
import kr.dku.councilflow.common.BaseEntity;

/** 초대된 계정. 비밀번호는 BCrypt 해시로만 저장한다. 권한은 Membership 이 정한다. */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    /** 서비스 관리자: 그룹 만들기, owner 부여·회수, 모든 그룹 읽기 전용 열람 */
    @Column(nullable = false)
    private boolean serviceAdmin;

    protected AppUser() {
    }

    public AppUser(String email, String passwordHash, String name) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.name = name;
    }

    public void grantServiceAdmin() {
        this.serviceAdmin = true;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public boolean isServiceAdmin() {
        return serviceAdmin;
    }
}
