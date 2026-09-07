package com.star_pick.starpick.domain.upload.repository;

import com.star_pick.starpick.domain.upload.domain.UploadObject;
import com.star_pick.starpick.domain.upload.domain.UploadPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadObjectRepository extends JpaRepository<UploadObject, String> {

    /**
     * 미연결 상태일 때만 연결한다. 갱신된 행 수를 반환하며 1이면 성공이다.
     *
     * <p>조회 후 검사하고 저장하는 방식은 두 문장 사이에 다른 요청이 끼어들 수 있어 쓰지 않는다.
     * 조건을 WHERE 에 넣어 한 문장으로 처리해야 "하나의 Key 는 평생 한 번만 연결" 이 보장된다.
     *
     * <p><b>{@code clearAutomatically = true} 를 붙이지 말 것.</b> 이 메서드는 Recipe 수정
     * 트랜잭션 안에서 호출되는데, 그 시점에는 제목·재료 등 앞서 적용한 변경이 아직 flush 되지
     * 않았다. 벌크 UPDATE 대상({@code upload_object})이 대기 중 변경 대상({@code recipe} 등)과
     * 겹치지 않아 Hibernate 의 auto-flush 도 일어나지 않으므로, 컨텍스트를 비우면 그 변경이
     * 전부 사라지고 응답만 200 으로 나간다. 이 경로에서 UploadObject 는 영속성 컨텍스트에
     * 올라온 적이 없어 1차 캐시가 stale 해질 위험도 없다.
     */
    @Modifying
    @Query("""
            update UploadObject u
               set u.attachedAt = CURRENT_TIMESTAMP
             where u.objectKey = :objectKey
               and u.userId = :userId
               and u.purpose = :purpose
               and u.attachedAt is null
            """)
    int attachIfUnattached(@Param("objectKey") String objectKey,
                           @Param("userId") Long userId,
                           @Param("purpose") UploadPurpose purpose);

    /**
     * 소유자와 용도까지 확인해 제거한다. 하나라도 어긋나면 0을 반환하고 아무것도 지우지 않는다.
     *
     * <p>용도까지 보는 이유: 소유자만 확인하면, 호출 도메인이 실수로 같은 사용자의 다른 용도
     * Key(예: Recipe 가 CookHistory 사진 Key)를 넘겼을 때 아직 참조 중인 행이 지워진다. 그 뒤
     * 커밋 후 정리가 실제 파일까지 지우고, UploadObject 행이 없으니 재연결도 불가능해진다.
     */
    @Modifying
    @Query("""
            delete from UploadObject u
             where u.objectKey = :objectKey
               and u.userId = :userId
               and u.purpose = :purpose
            """)
    int deleteOwned(@Param("objectKey") String objectKey,
                    @Param("userId") Long userId,
                    @Param("purpose") UploadPurpose purpose);
}
