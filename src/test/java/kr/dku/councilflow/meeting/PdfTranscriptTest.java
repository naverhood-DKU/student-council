package kr.dku.councilflow.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.List;
import kr.dku.councilflow.IntegrationTest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

/** PDF 회의록 → 글자 뽑기. 테스트 PDF는 PDFBox 로 직접 만든다(기본 글꼴이라 영문). */
class PdfTranscriptTest extends IntegrationTest {

    /** 줄마다 글자를 찍은 PDF. 빈 목록이면 글자 없는 쪽 하나(스캔본 흉내). */
    static byte[] pdf(List<String> lines) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (!lines.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setLeading(16);
                    content.newLineAtOffset(50, 700);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void PDF에서_글자를_뽑고_줄바꿈으로_끊긴_발언은_이어_붙인다() throws Exception {
        byte[] file = pdf(List.of(
                "Council meeting minutes",
                "Chair: We will run the festival booth",
                "on October 23 only.",
                "Planner: Agreed.",
                "- 1 -"));

        PdfTranscriptExtractor.Extracted extracted = PdfTranscriptExtractor.extract(file);

        assertThat(extracted.pageCount()).isEqualTo(1);
        assertThat(extracted.text().split("\n")).containsExactly(
                "Council meeting minutes",
                "Chair: We will run the festival booth on October 23 only.",
                "Planner: Agreed.");
    }

    @Test
    void 이름_콜론_형식이_아닌_회의록은_줄을_그대로_둔다() {
        assertThat(PdfTranscriptExtractor.tidy("""
                축제 부스를 23일 하루 운영하기로 했다.
                홍보는 홍보부가 맡는다.
                3 / 10
                """)).isEqualTo("축제 부스를 23일 하루 운영하기로 했다.\n홍보는 홍보부가 맡는다.");
    }

    @Test
    void 한국어_회의록도_이어지는_줄을_앞_발언에_붙인다() {
        assertThat(PdfTranscriptExtractor.tidy("""
                제5차 정기회의 회의록
                회장: 축제 부스는 10월 23일 하루
                운영하는 것으로 확정하겠습니다.
                기획부장: 네, 동의합니다.
                2페이지
                """)).isEqualTo("""
                제5차 정기회의 회의록
                회장: 축제 부스는 10월 23일 하루 운영하는 것으로 확정하겠습니다.
                기획부장: 네, 동의합니다.""");
    }

    @Test
    void 업로드_API는_회의를_올릴_수_있는_역할만_쓴다() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        MockMultipartFile file = new MockMultipartFile("file", "minutes.pdf", "application/pdf",
                pdf(List.of("Chair: Hello", "Planner: Hi")));

        mvc.perform(multipart("/api/meetings/extract-text").file(file).param("orgId", String.valueOf(cse))
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Chair: Hello\nPlanner: Hi"))
                .andExpect(jsonPath("$.pageCount").value(1));

        mvc.perform(multipart("/api/meetings/extract-text").file(file).param("orgId", String.valueOf(cse))
                        .header(HttpHeaders.AUTHORIZATION, bearer("ce.plan")))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/meetings/extract-text").file(file).param("orgId", String.valueOf(cse))
                        .header(HttpHeaders.AUTHORIZATION, bearer("sw")))
                .andExpect(status().isForbidden());
    }

    @Test
    void PDF가_아니거나_글자가_없으면_400과_이유() throws Exception {
        long cse = orgId("컴퓨터공학과 학생회");
        String chair = bearer("ce");

        mvc.perform(multipart("/api/meetings/extract-text")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "hello".getBytes()))
                        .param("orgId", String.valueOf(cse))
                        .header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("PDF 파일이 아닙니다."));

        mvc.perform(multipart("/api/meetings/extract-text")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf(List.of())))
                        .param("orgId", String.valueOf(cse))
                        .header(HttpHeaders.AUTHORIZATION, chair))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("스캔한 이미지 PDF")));
    }
}
