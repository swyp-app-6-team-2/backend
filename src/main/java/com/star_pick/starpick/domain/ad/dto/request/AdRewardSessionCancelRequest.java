package com.star_pick.starpick.domain.ad.dto.request;

import com.star_pick.starpick.domain.ad.entity.AdRewardCancelReason;
import jakarta.validation.constraints.NotNull;

public record AdRewardSessionCancelRequest(
        @NotNull(message = "reason은 필수입니다.")
        AdRewardCancelReason reason
) {
}
