package com.star_pick.starpick.domain.notification.service;

import java.util.List;

/** 메시지마다 결과를 돌려준다. 호출 실패도 예외가 아니라 {@link PushOutcome#FAILED} 결과로 돌려준다. */
public interface PushGateway {

    List<PushSendResult> send(List<PushMessage> messages);
}
