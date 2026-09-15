package com.star_pick.starpick.admin.controller;

import com.star_pick.starpick.domain.inquiry.domain.InquiryStatus;
import com.star_pick.starpick.domain.inquiry.domain.InquiryType;
import com.star_pick.starpick.domain.inquiry.repository.AdminInquiryDetailRow;
import com.star_pick.starpick.domain.inquiry.repository.AdminInquiryRow;
import com.star_pick.starpick.domain.inquiry.service.AdminInquiryDetail;
import com.star_pick.starpick.domain.inquiry.service.AdminInquiryPage;
import com.star_pick.starpick.domain.inquiry.service.InquiryAdminService;
import com.star_pick.starpick.global.exception.BusinessException;
import com.star_pick.starpick.global.exception.CommonErrorCode;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 문의 목록·상세·답변.
 *
 * <p>시각·상태 문구는 여기서 문자열로 만들어 넘긴다. 템플릿이 java.time 을 직접 다루지 않게 한다.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/inquiries")
public class AdminInquiryController {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /** 앱 문의 화면과 같은 표시 이름. 칩·카드·상세가 함께 쓴다. */
    private static final Map<InquiryType, String> TYPE_LABELS = new EnumMap<>(Map.of(
            InquiryType.RECIPE, "레시피",
            InquiryType.SLOT, "별 슬롯 확장",
            InquiryType.ACCOUNT, "계정·로그인",
            InquiryType.NOTIFICATION, "알림",
            InquiryType.BUG, "오류 신고",
            InquiryType.ETC, "제안·기타"));

    private static final Map<InquiryStatus, String> STATUS_LABELS = new EnumMap<>(Map.of(
            InquiryStatus.RECEIVED, "접수완료",
            InquiryStatus.ANSWERED, "답변완료"));

    private static final Map<String, String> PROVIDER_LABELS = Map.of(
            "KAKAO", "카카오", "NAVER", "네이버", "GOOGLE", "구글", "APPLE", "애플");

    private final InquiryAdminService inquiryAdminService;

    /** {@code status}·{@code type} 이 빈 문자열이면 null(전체), 없는 값이면 타입 변환 실패로 400 화면이다. */
    @GetMapping
    public String list(@RequestParam(required = false) InquiryStatus status,
                       @RequestParam(required = false) InquiryType type,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        if (page < 0) {
            throw new BusinessException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }
        AdminInquiryPage result = inquiryAdminService.getInquiries(status, type, page);
        model.addAttribute("result", result);
        model.addAttribute("rows", result.inquiries().stream().map(RowView::from).toList());
        model.addAttribute("status", status);
        model.addAttribute("type", type);
        model.addAttribute("statusLabels", STATUS_LABELS);
        model.addAttribute("typeLabels", TYPE_LABELS);
        return "admin/inquiries";
    }

    @GetMapping("/{inquiryId}")
    public String detail(@PathVariable Long inquiryId, Model model) {
        AdminInquiryDetail detail = inquiryAdminService.getInquiry(inquiryId);
        model.addAttribute("inquiry", DetailView.from(detail));
        if (!model.containsAttribute("answerForm")) {
            model.addAttribute("answerForm", new AnswerForm(detail.row().answer()));
        }
        return "admin/inquiry";
    }

    /** 검증에 실패하면 저장하지 않고 입력을 유지한 채 상세를 다시 보여준다. 성공하면 PRG. */
    @PostMapping("/{inquiryId}/answer")
    public String saveAnswer(@PathVariable Long inquiryId,
                             @Valid @ModelAttribute("answerForm") AnswerForm answerForm,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("inquiry", DetailView.from(inquiryAdminService.getInquiry(inquiryId)));
            return "admin/inquiry";
        }
        inquiryAdminService.saveAnswer(inquiryId, answerForm.getAnswer());
        redirectAttributes.addFlashAttribute("saved", true);
        return "redirect:/admin/inquiries/" + inquiryId;
    }

    private static String format(Instant instant) {
        return instant == null ? null : DISPLAY.format(instant);
    }


    /** 탈퇴한 작성자는 목록·상세 모두 닉네임 대신 표시한다. 프로필이 없으면 null(빈 칸)이다. */
    private static String writerLabel(boolean withdrawn, String nickname) {
        return withdrawn ? "탈퇴한 사용자" : nickname;
    }

    public record RowView(Long inquiryId, String createdAt, String typeLabel, String title,
                          String writer, String status, boolean answered) {

        static RowView from(AdminInquiryRow row) {
            return new RowView(row.inquiryId(), format(row.createdAt()), TYPE_LABELS.get(row.type()), row.title(),
                    writerLabel(row.withdrawn(), row.nickname()), STATUS_LABELS.get(row.status()),
                    row.status() == InquiryStatus.ANSWERED);
        }
    }

    public record DetailView(Long inquiryId, Long userId, String writer, String loginProvider,
                             String typeLabel, String title, String content, String createdAt,
                             List<String> attachmentImageUrls, String status, String answeredAt) {

        static DetailView from(AdminInquiryDetail detail) {
            AdminInquiryDetailRow row = detail.row();
            InquiryStatus status = row.answer() == null ? InquiryStatus.RECEIVED : InquiryStatus.ANSWERED;
            return new DetailView(row.inquiryId(), row.userId(), writerLabel(row.withdrawn(), row.nickname()),
                    PROVIDER_LABELS.getOrDefault(row.lastLoginProvider(), "알 수 없음"), TYPE_LABELS.get(row.type()),
                    row.title(), row.content(), format(row.createdAt()),
                    detail.attachmentImageUrls(), STATUS_LABELS.get(status), format(row.answeredAt()));
        }
    }
}
