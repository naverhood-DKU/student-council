package kr.dku.councilflow.storage;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로컬·테스트에서 Object Storage 의 presigned 업로드 주소를 흉내 낸다. 받은 녹음을 FakeObjectStorage 폴더에 저장한다.
 * 운영(prod)에는 없다(운영은 버킷의 presigned 주소로 직접 올린다). 키는 회의를 만들 때 서버가 정한 recordings/UUID.확장자 모양만 받는다.
 */
@RestController
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "local", matchIfMissing = true)
// 로그인 없이 받는 주소라 운영에서는 설정과 상관없이 열지 않는다.
@Profile("!prod")
@CrossOrigin(origins = "*", methods = {RequestMethod.PUT, RequestMethod.GET, RequestMethod.OPTIONS})
public class FakeStorageController {

    private static final String PREFIX = "/fake-storage/upload/";
    /** 녹음 업로드 한도(MeetingController 와 같은 100MB). */
    static final long MAX_BYTES = 100L * 1024 * 1024;

    private final FakeObjectStorage storage;

    public FakeStorageController(FakeObjectStorage storage) {
        this.storage = storage;
    }

    @PutMapping(PREFIX + "**")
    public ResponseEntity<Void> handleFakeUpload(HttpServletRequest request) throws IOException {
        String uri = request.getRequestURI();
        int at = uri.indexOf(PREFIX);
        String objectKey = at < 0 ? "" : uri.substring(at + PREFIX.length());
        if (request.getContentLengthLong() > MAX_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        try (InputStream in = request.getInputStream()) {
            storage.store(objectKey, in, MAX_BYTES);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (FakeObjectStorage.TooLargeException e) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
    }
}
