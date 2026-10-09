package kr.dku.councilflow.auth;

import java.time.Instant;
import kr.dku.councilflow.user.AppUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * 액세스 토큰 발급. 토큰에는 사용자 id(sub)만 넣고 역할·소속은 넣지 않는다.
 * 권한은 요청마다 DB의 활성 Membership 으로 확인하므로, 소속을 끊으면 토큰이 남아 있어도 바로 막힌다.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public TokenResponse issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.ttl().toSeconds());
    }
}
