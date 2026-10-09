package kr.dku.councilflow.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 계정: 비밀번호 바꾸기. 관리자·owner 가 정해 준 초기 비밀번호는 첫 로그인 뒤 바꾼다. */
@RestController
class AccountController {

    private final AppUserRepository users;
    private final UserAccounts accounts;

    AccountController(AppUserRepository users, UserAccounts accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    record ChangePassword(@NotBlank @Size(max = 100) String currentPassword,
                          @NotBlank @Size(max = 100) String newPassword) {
    }

    @PostMapping("/api/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(CurrentUser currentUser, @Valid @RequestBody ChangePassword request) {
        AppUser user = users.findById(currentUser.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!accounts.matches(request.currentPassword(), user)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지금 비밀번호가 맞지 않습니다.");
        }
        user.changePasswordHash(accounts.encodeNew(request.newPassword()));
    }
}
