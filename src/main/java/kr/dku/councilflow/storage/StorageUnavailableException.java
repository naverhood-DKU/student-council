package kr.dku.councilflow.storage;

/** 녹음 저장소를 쓸 수 없음(운영에서 버킷·키 설정이 비어 있음). 앱은 뜨고, 녹음 업로드·전사만 막는다. */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException() {
        super("STORAGE_NOT_CONFIGURED");
    }
}
