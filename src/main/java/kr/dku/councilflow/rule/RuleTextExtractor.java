package kr.dku.councilflow.rule;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 규정 파일에서 글자를 뽑는다. 회의록용 PdfTranscriptExtractor 와 달리 줄을 합치거나 다듬지 않는다
 * (조항 원문은 있는 그대로 보존해야 하므로). 쪽번호 줄 제거는 RuleTextSplitter 가 한다.
 * 원본 파일은 저장하지 않는다.
 */
public final class RuleTextExtractor {

    public static final int MAX_PAGES = 200;

    private RuleTextExtractor() {
    }

    public static String extract(byte[] bytes, String fileName) {
        boolean pdf = bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        return pdf ? fromPdf(bytes) : fromText(bytes, fileName);
    }

    private static String fromPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "PDF가 너무 깁니다(" + document.getNumberOfPages() + "쪽). " + MAX_PAGES + "쪽 이하로 올려 주세요.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "PDF에서 글자를 찾지 못했습니다. 스캔한 이미지 PDF는 지원하지 않습니다. 글자를 직접 붙여 넣어 주세요.");
            }
            return text;
        } catch (InvalidPasswordException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "암호가 걸린 PDF는 읽을 수 없습니다. 암호를 풀고 올려 주세요.");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "PDF를 읽지 못했습니다. 파일이 손상되었을 수 있습니다.");
        }
    }

    private static String fromText(byte[] bytes, String fileName) {
        if (fileName != null && !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "PDF 또는 TXT 파일만 올릴 수 있습니다.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "TXT 파일은 UTF-8 로 저장해서 올려 주세요.");
        }
    }
}
