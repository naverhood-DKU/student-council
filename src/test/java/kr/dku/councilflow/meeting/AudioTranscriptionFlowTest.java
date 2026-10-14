package kr.dku.councilflow.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 녹음 회의: 업로드 주소 받기 → 로컬 저장소에 파일 저장 → 전사 작업.
 * 테스트는 SPEECH_MODE 와 상관없이 데모 전사(fake)를 쓰고, 결과에 "(데모 전사)"가 붙는다.
 */
class AudioTranscriptionFlowTest extends IntegrationTest {

    @Autowired
    JobRunner jobRunner;

    @Autowired
    FakeObjectStorage storage;

    @Test
    void 올린_녹음이_저장되고_데모_전사는_데모라고_표시된다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String chair = bearer("ce");

        String created = mvc.perform(post("/api/orgs/{id}/meetings/audio/upload-url", cse)
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgId": %d, "title": "할로윈 행사 회의", "heldAt": "2026-10-14T09:00:00Z",
                                 "filename": "halloween.m4a", "fileSizeBytes": 4, "consentConfirmed": true}
                                """.formatted(cse)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long meetingId = ((Number) JsonPath.read(created, "$.meetingId")).longValue();
        String objectKey = JsonPath.read(created, "$.objectKey");

        // 브라우저가 presigned 주소로 PUT 하는 것과 같다. 받은 바이트가 그대로 저장된다.
        mvc.perform(put("/fake-storage/upload/" + objectKey).content(new byte[] {9, 8, 7, 6}))
                .andExpect(status().isOk());
        assertThat(Files.readAllBytes(storage.find(objectKey).orElseThrow())).containsExactly(9, 8, 7, 6);
        mvc.perform(put("/fake-storage/upload/recordings/x.exe").content(new byte[] {1}))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/meetings/{id}/transcribe", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(status().isOk());
        mvc.perform(get("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(jsonPath("$.latestJob.type").value("TRANSCRIBE"))
                .andExpect(jsonPath("$.latestJob.status").value("QUEUED"));
        assertThat(jobRunner.runOnce()).isTrue();

        mvc.perform(get("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(jsonPath("$.latestJob.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.segmentCount").value(3))
                .andExpect(jsonPath("$.segments", hasSize(3)))
                .andExpect(jsonPath("$.segments[0].speaker").value("화자1"))
                .andExpect(jsonPath("$.segments[0].text", startsWith("(데모 전사)")));
    }

    @Test
    void 녹음_회의를_지우면_녹음_원본_파일도_지운다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String chair = bearer("ce");
        String created = mvc.perform(post("/api/orgs/{id}/meetings/audio/upload-url", cse)
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgId": %d, "title": "지울 녹음 회의", "filename": "a.mp3", "fileSizeBytes": 2,
                                 "consentConfirmed": true}
                                """.formatted(cse)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long meetingId = ((Number) JsonPath.read(created, "$.meetingId")).longValue();
        String objectKey = JsonPath.read(created, "$.objectKey");
        mvc.perform(put("/fake-storage/upload/" + objectKey).content(new byte[] {1, 2}))
                .andExpect(status().isOk());
        assertThat(storage.exists(objectKey)).isTrue();

        mvc.perform(get("/api/meetings/{id}/deletion-impact", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(jsonPath("$.hasRecording").value(true));
        mvc.perform(delete("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(status().is2xxSuccessful());

        assertThat(storage.exists(objectKey)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting WHERE id = ?", Integer.class, meetingId))
                .isZero();
    }

    @Test
    void 녹음이_올라오지_않았으면_전사하지_않고_이유를_남긴다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String chair = bearer("ce");
        String created = mvc.perform(post("/api/orgs/{id}/meetings/audio/upload-url", cse)
                        .header(HttpHeaders.AUTHORIZATION, chair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgId": %d, "title": "업로드 실패한 회의", "filename": "a.wav", "fileSizeBytes": 4,
                                 "consentConfirmed": true}
                                """.formatted(cse)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long meetingId = ((Number) JsonPath.read(created, "$.meetingId")).longValue();

        mvc.perform(post("/api/meetings/{id}/transcribe", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(status().isOk());
        assertThat(jobRunner.runOnce()).isTrue();

        mvc.perform(get("/api/meetings/{id}", meetingId).header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(jsonPath("$.latestJob.status").value("FAILED"))
                .andExpect(jsonPath("$.latestJob.lastError").value("SPEECH_AUDIO_MISSING"))
                .andExpect(jsonPath("$.segmentCount").value(0));
    }
}
