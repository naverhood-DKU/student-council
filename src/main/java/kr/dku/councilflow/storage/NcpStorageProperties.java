package kr.dku.councilflow.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * NCP Object Storage(운영 녹음 저장소) 설정. 버킷·키는 환경변수 OBJECT_STORAGE_BUCKET·OBJECT_STORAGE_ACCESS_KEY·
 * OBJECT_STORAGE_SECRET_KEY 로만 받는다. 키는 NCP 인증키(Object Storage 권한만 준 Sub Account 권장)다.
 *
 * @param uploadUrlTtl 브라우저에 주는 업로드 주소의 유효 시간
 * @param callTimeout  서버가 버킷에 묻는 호출 하나의 최대 시간(재시도 포함)
 */
@ConfigurationProperties("app.storage.ncp")
public record NcpStorageProperties(
        @DefaultValue("https://kr.object.ncloudstorage.com") String endpoint,
        @DefaultValue("kr-standard") String region,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("15m") Duration uploadUrlTtl,
        @DefaultValue("10m") Duration downloadUrlTtl,
        @DefaultValue("30s") Duration callTimeout) {

    /** 버킷과 키가 모두 있어야 쓸 수 있다. */
    public boolean configured() {
        return present(bucket) && present(accessKey) && present(secretKey);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
