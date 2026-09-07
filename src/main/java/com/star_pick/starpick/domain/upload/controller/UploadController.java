package com.star_pick.starpick.domain.upload.controller;

import com.star_pick.starpick.domain.upload.controller.request.UploadUrlIssueRequest;
import com.star_pick.starpick.domain.upload.controller.response.UploadUrlIssueResponse;
import com.star_pick.starpick.domain.upload.service.UploadService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/uploads")
@Tag(name = "이미지 업로드", description = "이미지 업로드 URL 발급 API")
public class UploadController {

    private final UploadService uploadService;

    /**
     * 201 이 아니라 200 이다. 이 API 가 만드는 것은 클라이언트가 받아 가는 리소스가 아니라
     * 저장소 접근 권한이고, UploadObject 저장은 내부 부수효과다.
     */
    @Operation(summary = "이미지 업로드 URL 발급",
            description = """
                    이미지를 저장소에 직접 올릴 수 있는 임시 URL을 발급합니다.
                    - 응답의 uploadUrl 로 이미지 바이너리를 PUT 합니다.
                    - uploadHeaders 를 그대로 포함해야 합니다. 서명에 포함된 값이라 하나라도 빠지면 저장소가 거부합니다.
                    - 업로드를 마친 뒤 objectKey 를 레시피·조리기록 API 에 전달합니다.
                    - URL 은 발급 시점부터 15분간 유효합니다.
                    """)
    @PostMapping("/images")
    public ApiResponse<UploadUrlIssueResponse> issueUploadUrl(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UploadUrlIssueRequest request) {

        UploadUrlIssueResponse response = uploadService.issueUploadUrl(
                user.userId(), request.purpose(), request.contentType());

        return ApiResponse.ok("이미지 업로드 URL이 발급되었습니다.", response);
    }
}
