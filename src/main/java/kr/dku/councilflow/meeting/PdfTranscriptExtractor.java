package kr.dku.councilflow.meeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF 회의록에서 글자만 뽑아 "한 줄 = 발언 하나" 모양에 가깝게 다듬는다.
 * 결과는 바로 저장하지 않고 화면의 회의록 칸에 채워서 사람이 확인·수정한 뒤 올린다.
 * 원본 PDF는 저장하지 않는다.
 *
 * 다듬기
 * - 쪽 번호만 있는 줄("- 3 -", "3 / 10", "3페이지")은 버린다.
 * - "이름: 내용" 줄이 많은 회의록이면, 이름 없이 이어지는 줄은 PDF 줄바꿈으로 보고 앞 발언에 붙인다.
 */
public final class PdfTranscriptExtractor {

    public static final int MAX_PAGES = 100;
    private static final Pattern PAGE_NUMBER =
            Pattern.compile("^[-–—\\s]*\\d{1,4}\\s*(/\\s*\\d{1,4})?\\s*(쪽|페이지|page)?[-–—\\s]*$", Pattern.CASE_INSENSITIVE);
    /** 이 비율 이상이 "이름: 내용" 줄이면 이어지는 줄을 앞 발언에 붙인다. */
    private static final double SPEAKER_RATIO_TO_JOIN = 0.3;

    private PdfTranscriptExtractor() {
    }

    public record Extracted(String text, int pageCount) {
    }

    public static Extracted extract(byte[] pdf) {
        if (pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "PDF 파일이 아닙니다.");
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "PDF가 너무 깁니다(" + pages + "쪽). " + MAX_PAGES + "쪽 이하로 나눠 올려 주세요.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = tidy(stripper.getText(document));
            if (text.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "PDF에서 글자를 찾지 못했습니다. 스캔한 이미지 PDF는 아직 지원하지 않습니다. 회의록을 직접 붙여 넣어 주세요.");
            }
            return new Extracted(text, pages);
        } catch (InvalidPasswordException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "암호가 걸린 PDF는 읽을 수 없습니다. 암호를 풀고 올려 주세요.");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "PDF를 읽지 못했습니다. 파일이 손상되었을 수 있습니다.");
        }
    }

    /** 뽑은 글자를 줄 단위로 다듬는다(쪽 번호 제거, 이어지는 줄 붙이기). */
    static String tidy(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !PAGE_NUMBER.matcher(stripped).matches()) {
                lines.add(stripped);
            }
        }
        long speakerLines = lines.stream().filter(TranscriptSplitter::isSpeakerLine).count();
        if (lines.isEmpty() || (double) speakerLines / lines.size() < SPEAKER_RATIO_TO_JOIN) {
            return String.join("\n", lines);
        }
        List<String> joined = new ArrayList<>();
        boolean seenSpeaker = false;
        for (String line : lines) {
            boolean speaker = TranscriptSplitter.isSpeakerLine(line);
            if (speaker) {
                seenSpeaker = true;
                joined.add(line);
            } else if (seenSpeaker) {
                int last = joined.size() - 1;
                joined.set(last, joined.get(last) + " " + line);
            } else {
                // 첫 발언 전의 제목·일시 같은 줄은 그대로 둔다.
                joined.add(line);
            }
        }
        return String.join("\n", joined);
    }
}
