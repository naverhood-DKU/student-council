package kr.dku.councilflow.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로컬·테스트용 저장소(app.storage.mode=local, prod 밖의 기본). Object Storage 대신 이 서버의 폴더(app.storage.local-dir)에
 * 녹음 파일을 저장한다. 올리는 주소는 FakeStorageController 이고, 실제 전사(SPEECH_MODE=clova)를 켜면 여기 저장된 파일을
 * CLOVA Speech 에 보낸다. 운영은 NcpObjectStorage 를 쓴다.
 */
@Component
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "local", matchIfMissing = true)
public class FakeObjectStorage implements ObjectStorage, LocalAudioFiles {

    /** MeetingController 가 만드는 키 모양만 받는다(경로 조작 방지). */
    private static final Pattern KEY = Pattern.compile("recordings/[A-Za-z0-9-]{1,64}\\.(m4a|mp3|wav)");

    private final Path root;

    public FakeObjectStorage(@Value("${app.storage.local-dir}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

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
        return find(objectKey).isPresent();
    }

    @Override
    public Optional<Path> find(String objectKey) {
        Path path = pathOf(objectKey);
        return path != null && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * 올라온 파일을 저장한다. 다 받은 뒤에만 제자리로 옮기므로 중간에 끊기면 남지 않는다.
     *
     * @return 저장한 바이트 수
     * @throws IllegalArgumentException 키 모양이 틀림
     * @throws TooLargeException maxBytes 를 넘음(저장하지 않음)
     */
    public long store(String objectKey, InputStream in, long maxBytes) {
        Path target = pathOf(objectKey);
        if (target == null) {
            throw new IllegalArgumentException("invalid key");
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            try {
                long size = copyAtMost(in, temp, maxBytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return size;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path pathOf(String objectKey) {
        if (objectKey == null || !KEY.matcher(objectKey).matches()) {
            return null;
        }
        Path path = root.resolve(objectKey).normalize();
        return path.startsWith(root) ? path : null;
    }

    private static long copyAtMost(InputStream in, Path temp, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(temp)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new TooLargeException();
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /** 한도를 넘은 업로드. */
    public static class TooLargeException extends RuntimeException {
        TooLargeException() {
            super("too large");
        }
    }
}
