package com.star_pick.starpick.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "user-withdrawal.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class UserWithdrawalSchedule {
    private final UserWithdrawalRecovery recovery;

    @Scheduled(fixedDelayString = "${user-withdrawal.recovery.delay-ms:60000}",
            initialDelayString = "${user-withdrawal.recovery.delay-ms:60000}")
    public void recover() { recovery.recoverBatch(); }
}
