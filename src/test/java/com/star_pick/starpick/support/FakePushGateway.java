package com.star_pick.starpick.support;

import com.star_pick.starpick.domain.notification.service.PushGateway;
import com.star_pick.starpick.domain.notification.service.PushMessage;
import com.star_pick.starpick.domain.notification.service.PushOutcome;
import com.star_pick.starpick.domain.notification.service.PushSendResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 기본은 전부 SENT. 토큰별 결과와 "보내는 순간" 끼워 넣을 동작을 지정할 수 있다. */
public class FakePushGateway implements PushGateway {

    private record Response(PushOutcome outcome, String errorCode) {
    }

    private final List<PushMessage> sent = new ArrayList<>();
    private final Map<Long, Response> responses = new HashMap<>();
    private Consumer<List<PushMessage>> onSend = messages -> { };
    private int calls;

    @Override
    public synchronized List<PushSendResult> send(List<PushMessage> messages) {
        calls++;
        sent.addAll(messages);
        onSend.accept(messages);
        return messages.stream().map(message -> {
            Response response = responses.getOrDefault(message.pushTokenId(), new Response(PushOutcome.SENT, null));
            return new PushSendResult(message.pushLogId(), message.pushTokenId(), response.outcome(), response.errorCode());
        }).toList();
    }

    public synchronized void respond(long pushTokenId, PushOutcome outcome, String errorCode) {
        responses.put(pushTokenId, new Response(outcome, errorCode));
    }

    public synchronized void onSend(Consumer<List<PushMessage>> action) {
        onSend = action;
    }

    public synchronized List<PushMessage> sent() {
        return List.copyOf(sent);
    }

    public synchronized int calls() {
        return calls;
    }

    public synchronized void clear() {
        sent.clear();
        responses.clear();
        onSend = messages -> { };
        calls = 0;
    }
}
