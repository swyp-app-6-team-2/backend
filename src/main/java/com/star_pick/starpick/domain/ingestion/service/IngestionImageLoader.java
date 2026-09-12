package com.star_pick.starpick.domain.ingestion.service;

import com.star_pick.starpick.domain.ingestion.config.IngestionProperties;
import com.star_pick.starpick.domain.ingestion.exception.IngestionInputException;
import com.star_pick.starpick.domain.upload.service.ObjectStorage;
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

    public List<InlineImage> load(List<String> objectKeys) {
        long total = 0;
        for (String key : objectKeys) {
            Long size = objectStorage.size(key);
            if (size == null) {
                throw new IngestionInputException("사진이 저장소에 없다");
            }
            total += size;
            if (total > properties.image().maxTotalBytes()) {
                throw new IngestionInputException("사진 합계가 상한을 넘었다");
            }
        }

        List<InlineImage> images = new ArrayList<>(objectKeys.size());
        for (String key : objectKeys) {
            String mimeType = mimeTypeOf(key);
            byte[] content = objectStorage.read(key);
            if (content == null) {
                throw new IngestionInputException("사진이 저장소에 없다");
            }
            images.add(new InlineImage(mimeType, content));
        }
        return images;
    }

    private String mimeTypeOf(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        throw new IngestionInputException("지원하지 않는 사진 형식이다");
    }
}
