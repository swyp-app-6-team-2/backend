package com.star_pick.starpick.domain.auth.client;

import com.star_pick.starpick.domain.user.entity.Provider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SocialUserInfoClientResolver {
    private final Map<Provider, SocialUserInfoClient> clients;

    public SocialUserInfoClientResolver(List<SocialUserInfoClient> clientList) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(SocialUserInfoClient::getProvider, Function.identity()));
    }

    public SocialUserInfoClient resolve(Provider provider) {
        SocialUserInfoClient client = clients.get(provider);
        if (client == null) {
            throw new UnsupportedOperationException(provider.getDisplayName() + "로그인은 아직 지원하지 않습니다.");
        }
        return client;
    }
}
