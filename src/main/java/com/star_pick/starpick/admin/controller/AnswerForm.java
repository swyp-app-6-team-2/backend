package com.star_pick.starpick.admin.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 답변 입력 폼. {@code th:field} 가 getter·setter 로 바인딩하므로 record 로 두지 않는다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AnswerForm {

    @NotBlank(message = "답변을 입력해 주세요.")
    @Size(max = 2000, message = "답변은 2000자를 넘을 수 없습니다.")
    private String answer;

    /**
     * 브라우저는 textarea 줄바꿈을 CRLF 로 보낸다. 그대로 두면 {@code \r} 까지 글자 수에 들어가고
     * 앱 상세 응답에도 섞이므로 바인딩할 때 LF 로 맞춘다.
     */
    public void setAnswer(String answer) {
        this.answer = answer == null ? null : answer.replace("\r\n", "\n");
    }
}
