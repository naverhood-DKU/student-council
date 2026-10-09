package kr.dku.councilflow.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * email 에는 이메일이나 아이디를 넣는다. 형식은 따지지 않고 저장된 값과 같은지만 본다
 * (로컬 시드 계정은 ce, ee 같은 짧은 아이디를 쓴다).
 */
public record LoginRequest(
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 100) String password) {
}
