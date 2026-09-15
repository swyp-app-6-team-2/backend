package com.star_pick.starpick.domain.inquiry.controller;

import com.star_pick.starpick.domain.inquiry.controller.request.InquiryCreateRequest;
import com.star_pick.starpick.domain.inquiry.controller.response.InquiryCreateResponse;
import com.star_pick.starpick.domain.inquiry.controller.response.InquiryDetailResponse;
import com.star_pick.starpick.domain.inquiry.controller.response.InquiryListResponse;
import com.star_pick.starpick.domain.inquiry.service.InquiryService;
import com.star_pick.starpick.global.ApiResponse;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import com.star_pick.starpick.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inquiries")
@Tag(name = "문의", description = "문의 접수·조회 API")
public class InquiryController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final InquiryService inquiryService;

    @Operation(summary = "문의 접수",
            description = """
                    문의를 접수합니다.
                    - 사진은 `INQUIRY_ATTACHMENT` 용도로 업로드한 Key 를 최대 5개까지 보냅니다. 생략하거나 null 이면 사진이 없습니다.
                    - Key 하나라도 사용할 수 없으면 문의를 저장하지 않습니다.
                    """)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InquiryCreateResponse> createInquiry(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody InquiryCreateRequest request) {

        return ApiResponse.created("문의가 접수되었습니다.",
                new InquiryCreateResponse(inquiryService.create(user.userId(), request)));
    }

    @Operation(summary = "내 문의 목록 조회",
            description = """
                    최근 1년 내 내 문의를 최신순으로 조회합니다.
                    - `totalCount` 는 페이지 크기가 아니라 전체 결과 수입니다.
                    - 사진과 답변은 상세에서만 제공합니다.
                    """)
    @GetMapping
    public ApiResponse<InquiryListResponse> getInquiries(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // RecipeController 와 같은 이유로 Bean Validation 대신 수동 검사한다(data.code 보장).
        if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }
        return ApiResponse.ok("문의 목록을 조회했습니다.", inquiryService.getInquiries(user.userId(), page, size));
    }

    @Operation(summary = "내 문의 상세 조회",
            description = "최근 1년 내 내 문의만 조회합니다. 없거나 다른 사용자의 문의이거나 1년이 지났으면 동일하게 404 입니다.")
    @GetMapping("/{inquiryId}")
    public ApiResponse<InquiryDetailResponse> getInquiry(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long inquiryId) {

        return ApiResponse.ok("문의를 조회했습니다.", inquiryService.getInquiry(user.userId(), inquiryId));
    }
}
