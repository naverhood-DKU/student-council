package kr.dku.councilflow.storage;

import java.net.URI;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 운영 녹음 저장소: NCP Object Storage(S3 호환, 비공개 버킷). app.storage.mode=ncp(prod 기본)일 때 쓴다.
 * - 브라우저는 서버가 만든 presigned PUT 주소로 버킷에 직접 올린다(버킷 CORS 에서 사이트 주소의 PUT 허용 필요).
 * - 전사는 CLOVA Speech 가 같은 버킷을 Object Storage 연동으로 읽는다(서버는 파일이 올라왔는지만 확인).
 * 버킷·키가 비어 있어도 앱은 뜨고, 녹음 기능만 StorageUnavailableException 으로 막는다(텍스트 회의록은 그대로 쓴다).
 */
@Component
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "ncp")
public class NcpObjectStorage implements ObjectStorage, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NcpObjectStorage.class);

    private final NcpStorageProperties properties;
    /** 설정이 비어 있으면 null. */
    private final S3Client s3;
    private final S3Presigner presigner;

    public NcpObjectStorage(NcpStorageProperties properties) {
        this.properties = properties;
        if (!properties.configured()) {
            // 값은 남기지 않고 어떤 설정이 필요한지만 알린다.
            log.warn("NCP Object Storage 설정(OBJECT_STORAGE_BUCKET, OBJECT_STORAGE_ACCESS_KEY, OBJECT_STORAGE_SECRET_KEY)이"
                    + " 비어 있어 녹음 업로드·전사를 쓸 수 없습니다.");
            this.s3 = null;
            this.presigner = null;
            return;
        }
        URI endpoint = URI.create(properties.endpoint());
        Region region = Region.of(properties.region());
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        // 주소에 버킷 이름을 경로로 넣는다(https://kr.object.ncloudstorage.com/버킷/키).
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        // 지역·키를 모두 직접 넣는다(AWS 기본 설정 찾기, EC2 메타데이터 조회를 하지 않게).
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                // SDK 2.30 부터 기본으로 붙는 체크섬 헤더는 S3 호환 저장소가 모를 수 있어, 꼭 필요할 때만 쓴다.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.apiCallTimeout(properties.callTimeout()))
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    @Override
    public URL generatePresignedUploadUrl(String objectKey, long maxSizeBytes) {
        // presigned PUT 은 크기를 강제하지 못한다. 크기 제한은 업로드 주소를 줄 때 신고한 크기로만 확인한다.
        return presigner().presignPutObject(request -> request
                        .signatureDuration(properties.uploadUrlTtl())
                        .putObjectRequest(put -> put.bucket(properties.bucket()).key(objectKey)))
                .url();
    }

    @Override
    public URL generatePresignedDownloadUrl(String objectKey) {
        return presigner().presignGetObject(request -> request
                        .signatureDuration(properties.downloadUrlTtl())
                        .getObjectRequest(get -> get.bucket(properties.bucket()).key(objectKey)))
                .url();
    }

    /** 버킷에 그 키의 파일이 있는가. 없으면 false, 저장소 오류는 예외로 올린다(작업이 다시 시도한다). */
    @Override
    public boolean exists(String objectKey) {
        S3Client client = client();
        try {
            client.headObject(head -> head.bucket(properties.bucket()).key(objectKey));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private S3Client client() {
        if (s3 == null) {
            throw new StorageUnavailableException();
        }
        return s3;
    }

    private S3Presigner presigner() {
        if (presigner == null) {
            throw new StorageUnavailableException();
        }
        return presigner;
    }

    @Override
    public void destroy() {
        if (presigner != null) {
            presigner.close();
        }
        if (s3 != null) {
            s3.close();
        }
    }
}
