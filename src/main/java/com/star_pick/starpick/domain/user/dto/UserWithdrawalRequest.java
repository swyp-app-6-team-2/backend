package com.star_pick.starpick.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record UserWithdrawalRequest(
        @Schema(description = "소셜 계정 연결 해제용 access token. 네이버 로그인 사용자는 필수입니다.")
        @Size(max = 4096)
        String socialAccessToken
) {
}
