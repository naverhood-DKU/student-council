package kr.dku.councilflow.storage;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;

@RestController
@CrossOrigin(origins = "*", methods = {RequestMethod.PUT, RequestMethod.GET, RequestMethod.OPTIONS})
public class FakeStorageController {

    @PutMapping("/fake-storage/upload/**")
    public ResponseEntity<Void> handleFakeUpload(InputStream inputStream) {
        // 실제 음원 전송을 위한 스트림 수신 (필요 시 로컬 파일 저장 가능)
        return ResponseEntity.ok().build();
    }
}