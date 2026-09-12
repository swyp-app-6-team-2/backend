package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.exception.IngestionInputException;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
import com.star_pick.starpick.domain.upload.service.StoredObjectMetadata;
import com.star_pick.starpick.domain.upload.service.SupportedImageContentTypes;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IngestionImageLoader {

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
     */
    public List<InlineImage> load(List<String> objectKeys) {
        List<StoredObjectMetadata> metadata = new ArrayList<>(objectKeys.size());
        long total = 0;
        for (String key : objectKeys) {
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
            byte[] content = objectStorage.read(objectKeys.get(i));
            if (content == null) {
                throw new IngestionInputException("사진이 저장소에 없다");
            }
            images.add(new InlineImage(metadata.get(i).contentType(), content));
        }
        return images;
    }
}
