package com.star_pick.starpick.domain.ingestion.controller.request;

import com.star_pick.starpick.domain.ingestion.domain.IngestionInputType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

public record IngestionJobCreateRequest(
        @NotNull(message = "입력 종류는 필수입니다.")
        IngestionInputType inputType,

        @Size(max = 2048, message = "URL은 2048자를 넘을 수 없습니다.")
        String url,

        @Size(min = 1, max = MAX_INPUT_IMAGE_COUNT,
                message = "사진은 1장 이상 10장 이하여야 합니다.")
        List<@NotBlank(message = "사진 Key는 비어 있을 수 없습니다.") String> inputImageKeys) {

    public static final int MAX_INPUT_IMAGE_COUNT = 10;

    @AssertTrue(message = "입력 종류에 맞는 값 하나만 보내야 합니다.")
    public boolean isInputCombinationValid() {
        if (inputType == null) {
            return true;
        }
        return inputType == IngestionInputType.IMAGE
                ? url == null && inputImageKeys != null
                : url != null && inputImageKeys == null;
    }

    @AssertTrue(message = "사진 Key가 중복되었습니다.")
    public boolean isInputImageKeysUnique() {
        return inputImageKeys == null || new HashSet<>(inputImageKeys).size() == inputImageKeys.size();
    }
}
