package kr.dku.councilflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 로컬 저장소가 올라온 녹음을 실제로 저장하고, 서버가 만든 키 모양만 받는지. */
class FakeObjectStorageTest {

    @TempDir
    Path dir;

    @Test
    void 올린_파일을_저장하고_찾는다() throws Exception {
        FakeObjectStorage storage = new FakeObjectStorage(dir);
        String key = "recordings/3f1c2a4e-0000-4000-8000-000000000001.m4a";

        long size = storage.store(key, new ByteArrayInputStream(new byte[] {1, 2, 3}), 10);

        assertThat(size).isEqualTo(3);
        assertThat(storage.exists(key)).isTrue();
        assertThat(Files.readAllBytes(storage.find(key).orElseThrow())).containsExactly(1, 2, 3);
        assertThat(storage.exists("recordings/3f1c2a4e-0000-4000-8000-000000000002.m4a")).isFalse();
    }

    @Test
    void 서버가_만든_모양이_아닌_키는_받지_않는다() {
        FakeObjectStorage storage = new FakeObjectStorage(dir);
        for (String key : new String[] {"../x.m4a", "recordings/../../x.m4a", "recordings/a.exe",
                "recordings/a/b.m4a", "other/a.m4a", ""}) {
            assertThatThrownBy(() -> storage.store(key, new ByteArrayInputStream(new byte[] {1}), 10))
                    .as(key).isInstanceOf(IllegalArgumentException.class);
            assertThat(storage.find(key)).as(key).isEmpty();
        }
        assertThat(storage.find(null)).isEmpty();
    }

    @Test
    void 한도를_넘으면_저장하지_않고_임시_파일도_남기지_않는다() throws Exception {
        FakeObjectStorage storage = new FakeObjectStorage(dir);
        String key = "recordings/big.wav";

        assertThatThrownBy(() -> storage.store(key, new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(FakeObjectStorage.TooLargeException.class);

        assertThat(storage.exists(key)).isFalse();
        try (Stream<Path> left = Files.list(dir.resolve("recordings"))) {
            assertThat(left).isEmpty();
        }
    }
}
