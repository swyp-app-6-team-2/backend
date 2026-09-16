package com.star_pick.starpick.domain.ad.dto.request;

import com.star_pick.starpick.domain.ad.entity.AdRewardPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdRewardSessionCreateRequest(
        @NotNull(message = "platform은 필수입니다.")
        AdRewardPlatform platform,

        @NotBlank(message = "requestId는 필수입니다.")
        @Size(max = 255, message = "requestId는 255자를 넘을 수 없습니다.")
        String requestId
) {
}
