package kr.dku.councilflow.analysis;

import java.util.ArrayList;
import java.util.List;
import kr.dku.councilflow.ai.StudioClient;
import kr.dku.councilflow.ai.StudioClient.Message;
import kr.dku.councilflow.ai.StudioClient.StudioReply;
import kr.dku.councilflow.ai.StudioClient.StudioRequest;
import kr.dku.councilflow.ai.StudioException;
import kr.dku.councilflow.job.JobFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 회의 하나를 AI로 분석한다(DB 트랜잭션 밖에서 돈다).
 * - 긴 회의는 발언 ID를 유지한 채 구간으로 나눠 보내고 결과를 합친다.
 * - 응답 형식이 틀리면 정확히 한 번만 다시 요청한다. 두 번째도 틀리면 실패.
 */
@Component
public class MeetingAnalyzer {

    private final StudioClient studio;
    private final AnalysisPrompt prompt;
    private final AiResponseParser parser;
    private final int chunkChars;

    MeetingAnalyzer(StudioClient studio, AnalysisPrompt prompt, ObjectMapper json,
                    @Value("${app.analysis.chunk-chars:12000}") int chunkChars) {
        this.studio = studio;
        this.prompt = prompt;
        this.parser = new AiResponseParser(json);
        this.chunkChars = chunkChars;
    }

    public AiAnalysis analyze(MeetingSnapshot meeting) {
        List<List<MeetingSnapshot.Line>> chunks = chunks(meeting.lines(), chunkChars);
        List<AiAnalysis> parts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            parts.add(analyzeChunk(prompt.messages(meeting, chunks.get(i), i + 1, chunks.size())));
        }
        return AnalysisMerger.merge(parts);
    }

    private AiAnalysis analyzeChunk(List<Message> messages) {
        StudioReply first = call(messages);
        try {
            return parser.parse(first.content());
        } catch (AiResponseParser.InvalidResponse invalid) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(Message.assistant(first.content()));
            retry.add(AnalysisPrompt.retryMessage(invalid.getMessage()));
            StudioReply second = call(retry);
            try {
                return parser.parse(second.content());
            } catch (AiResponseParser.InvalidResponse again) {
                throw JobFailure.permanent("length".equals(second.finishReason())
                        ? "AI_RESPONSE_TRUNCATED"
                        : "AI_INVALID_RESPONSE");
            }
        }
    }

    private StudioReply call(List<Message> messages) {
        try {
            return studio.chat(new StudioRequest(messages, prompt.schema()));
        } catch (StudioException e) {
            throw switch (e.kind()) {
                case RETRYABLE -> JobFailure.retryable(e.code());
                case PERMANENT -> JobFailure.permanent(e.code());
                case UNKNOWN_OUTCOME -> JobFailure.unknownOutcome(e.code());
            };
        }
    }

    /** 글자 수 기준으로 발언을 순서대로 묶는다. 발언 하나는 쪼개지 않는다. */
    static List<List<MeetingSnapshot.Line>> chunks(List<MeetingSnapshot.Line> lines, int maxChars) {
        List<List<MeetingSnapshot.Line>> chunks = new ArrayList<>();
        List<MeetingSnapshot.Line> current = new ArrayList<>();
        int size = 0;
        for (MeetingSnapshot.Line line : lines) {
            int length = line.text().length() + (line.speaker() == null ? 0 : line.speaker().length()) + 8;
            if (!current.isEmpty() && size + length > maxChars) {
                chunks.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(line);
            size += length;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
