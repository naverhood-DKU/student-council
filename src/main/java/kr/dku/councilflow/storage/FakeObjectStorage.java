package kr.dku.councilflow.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URL;

@Component
@Profile({"local", "test", "fake-ai", "default"})
public class FakeObjectStorage implements ObjectStorage {

    @Override
    public URL generatePresignedUploadUrl(String objectKey, long maxSizeBytes) {
        try {
            return URI.create("http://localhost:8080/fake-storage/upload/" + objectKey).toURL();
        } catch (Exception e) {
            throw new RuntimeException("Fake Presigned URL 생성 실패", e);
        }
    }

    @Override
    public URL generatePresignedDownloadUrl(String objectKey) {
        try {
            return URI.create("http://localhost:8080/fake-storage/download/" + objectKey).toURL();
        } catch (Exception e) {
            throw new RuntimeException("Fake Presigned URL 생성 실패", e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return true;
    }
}