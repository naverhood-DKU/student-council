package kr.dku.councilflow.storage;

import java.net.URL;

public interface ObjectStorage {
    URL generatePresignedUploadUrl(String objectKey, long maxSizeBytes);
    URL generatePresignedDownloadUrl(String objectKey);
    boolean exists(String objectKey);
}

