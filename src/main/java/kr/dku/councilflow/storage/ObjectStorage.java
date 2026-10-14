package kr.dku.councilflow.storage;

import java.net.URL;

public interface ObjectStorage {
    URL generatePresignedUploadUrl(String objectKey, long maxSizeBytes);
    URL generatePresignedDownloadUrl(String objectKey);
    boolean exists(String objectKey);

    /** 파일을 지운다. 없으면 아무 일도 하지 않는다(회의를 지울 때 녹음 원본을 함께 지운다). */
    void delete(String objectKey);
}

