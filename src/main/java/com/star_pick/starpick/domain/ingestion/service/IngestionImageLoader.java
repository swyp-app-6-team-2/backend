package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.exception.IngestionInputException;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import com.star_pick.starpick.domain.upload.service.StoredObjectMetadata;
import com.star_pick.starpick.domain.upload.service.SupportedImageContentTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IngestionImageLoader {

    /** 이만큼도 안 남았으면 새 호출을 시작하지 않는다. 시작해봐야 결과를 쓰지 못한다. */
    private static final Duration MIN_REMAINING = Duration.ofSeconds(5);

    private final ObjectStorage objectStorage;
    private final IngestionProperties properties;

    public IngestionImageLoader(ObjectStorage objectStorage, IngestionProperties properties) {
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    /**
     * 분석에 넣을 사진을 Key 순서대로 읽는다.
     *
     * <p><b>바이트를 하나도 받기 전에 전부 검사한다.</b> 크기 합계를 먼저 보는 것은 같은 JVM 의
     * 메모리를 지키기 위해서고, 형식도 같은 자리에서 본다 — 마지막 장이 잘못된 형식일 때 앞의
     * 사진들을 이미 다 내려받은 뒤에야 실패하면 그 대역폭과 deadline 이 그냥 버려진다.
     *
     * <p><b>{@code deadline} 을 받는 이유.</b> 저장소 호출마다 자체 timeout 이 있지만 그것은
     * 한 건의 상한일 뿐이라, 사진이 여러 장이면 합계가 Job 전체 예산을 넘어설 수 있다. 그러면
     * Job 은 살아 있는데 stale 기준(3분)을 넘겨 주기 작업이 중복 시도를 시작하고, 그 사이 원래
     * 워커는 실행 슬롯을 계속 차지한다. 호출을 시작하기 전에 남은 시간을 보고 끊는다.
     */
    public List<InlineImage> load(List<String> objectKeys, Instant deadline) {
        List<StoredObjectMetadata> metadata = new ArrayList<>(objectKeys.size());
        long total = 0;
        for (String key : objectKeys) {
            ensureTimeLeft(deadline);
            StoredObjectMetadata found = objectStorage.metadata(key);
            if (found == null) {
                throw new IngestionInputException("사진이 저장소에 없다");
            }
            if (!SupportedImageContentTypes.matches(found.contentType())) {
                throw new IngestionInputException("지원하지 않는 사진 형식이다");
            }
            total += found.size();
            if (total > properties.image().maxTotalBytes()) {
                throw new IngestionInputException("사진 합계가 상한을 넘었다");
            }
            metadata.add(found);
        }

        List<InlineImage> images = new ArrayList<>(objectKeys.size());
        for (int i = 0; i < objectKeys.size(); i++) {
            ensureTimeLeft(deadline);
            byte[] content = objectStorage.read(objectKeys.get(i));
            if (content == null) {
                throw new IngestionInputException("사진이 저장소에 없다");
            }
            images.add(new InlineImage(metadata.get(i).contentType(), content));
        }
        return images;
    }

    /**
     * 이미 시작한 호출은 끊지 않는다. 저장소 클라이언트가 자체 timeout 을 갖고 있고, 진행 중인
     * 전송을 중간에 버리면 그때까지 받은 것도 함께 버려진다. 여기서 막는 것은 <b>끝낼 수 없는
     * 호출을 새로 시작하는 것</b>이다.
     */
    private void ensureTimeLeft(Instant deadline) {
        if (Duration.between(Instant.now(), deadline).compareTo(MIN_REMAINING) < 0) {
            throw new IngestionInputException("남은 시간 안에 사진을 받을 수 없다");
        }
    }
}
