package kr.dku.councilflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.dku.councilflow.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영처럼 저장소를 ncp 로 두고 버킷·키를 비웠을 때: 앱은 뜨고(텍스트 회의록 등은 그대로), 녹음 업로드만 503 으로 막는다.
 * 로그인 없이 받는 로컬 업로드 주소(FakeStorageController)는 없다.
 */
@TestPropertySource(properties = "app.storage.mode=ncp")
class NcpStorageWiringTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ObjectStorage storage;

    @Test
    void 버킷_설정이_없어도_앱은_뜨고_녹음_업로드만_막는다() throws Exception {
        assertThat(storage).isInstanceOf(NcpObjectStorage.class);
        assertThat(context.getBeanNamesForType(FakeStorageController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FakeObjectStorage.class)).isEmpty();

        long cse = orgId("컴퓨터공학과 학생회");
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM meeting WHERE org_id = ?", Integer.class, cse);
        mvc.perform(post("/api/orgs/{id}/meetings/audio/upload-url", cse)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgId": %d, "title": "녹음 회의", "filename": "a.m4a", "fileSizeBytes": 4,
                                 "consentConfirmed": true}
                                """.formatted(cse)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        // 업로드 주소를 못 만들면 회의도 만들지 않는다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting WHERE org_id = ?", Integer.class, cse))
                .isEqualTo(before);
    }
}
