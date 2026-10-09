package kr.dku.councilflow.auth;

import java.util.Optional;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    /** 없는 계정에도 같은 시간만큼 해시 비교를 해서, 응답 시간으로 계정 존재 여부를 알 수 없게 한다. */
    private final String dummyHash;

    LoginService(AppUserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Optional<AppUser> user = users.findByEmail(AppUser.normalizeEmail(request.email()));
        String hash = user.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return tokenService.issue(user.get());
    }
}
