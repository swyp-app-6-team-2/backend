package com.star_pick.starpick.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProfileUpdateRequest {
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 1, max = 6, message = "닉네임은 1자 이상 6자 이하로 입력해주세요.")
    private String nickname;

    // null: 생략(유지), Optional.empty(): 명시적 null(삭제).
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "생략하면 유지, null이면 제거, 값이면 업로드 objectKey로 교체")
    private Optional<@Size(min = 1, max = 255, message = "이미지 키는 1~255자여야 합니다.") String> profileImageKey;
}
