package kr.dku.councilflow.storage;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 녹음 파일을 이 서버의 디스크에 보관하는 저장소(로컬·테스트). 실제 전사를 시험할 때 CLOVA Speech 에 파일을 직접 보내려고 쓴다.
 * 운영(Object Storage)에는 이 구현이 없고, 전사는 Object Storage 연동 방식으로 한다.
 */
public interface LocalAudioFiles {

    /** 그 키로 올라온 파일. 없거나 키 모양이 틀리면 비어 있다. */
    Optional<Path> find(String objectKey);
}
