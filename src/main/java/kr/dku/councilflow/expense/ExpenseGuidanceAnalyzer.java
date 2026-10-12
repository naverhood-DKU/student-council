package kr.dku.councilflow.expense;

import java.util.ArrayList;
import java.util.List;
import kr.dku.councilflow.ai.StudioClient;
import kr.dku.councilflow.ai.StudioClient.Message;
import kr.dku.councilflow.ai.StudioClient.StudioReply;
import kr.dku.councilflow.ai.StudioClient.StudioRequest;
import kr.dku.councilflow.ai.StudioException;
import kr.dku.councilflow.analysis.AnalysisPrompt;
import kr.dku.councilflow.job.JobFailure;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 지출 안내를 AI 로 받는다(DB 트랜잭션 밖에서 돈다). MeetingAnalyzer 와 같은 규칙이다.
 * - 응답 형식이 틀리면 정확히 한 번만 다시 요청한다. 두 번째도 틀리면 실패(AI_INVALID_RESPONSE).
 * - 공급자가 처리했는지 알 수 없는 실패는 즉시 다시 부르지 않고 FAILED_UNKNOWN 으로 남긴다.
 * 모델에는 도구 실행 권한을 주지 않는다. 이 클래스는 받은 글을 JSON 모양으로 읽기만 한다.
 */
@Component
public class ExpenseGuidanceAnalyzer {

    private final StudioClient studio;
    private final ExpenseGuidancePrompt prompt;
    private final ExpenseGuidanceParser parser;

    ExpenseGuidanceAnalyzer(StudioClient studio, ExpenseGuidancePrompt prompt, ObjectMapper json) {
        this.studio = studio;
        this.prompt = prompt;
        this.parser = new ExpenseGuidanceParser(json);
    }

    public ParsedGuidance analyze(List<Message> messages) {
        StudioReply first = call(messages);
        try {
            return parser.parse(first.content());
        } catch (ExpenseGuidanceParser.InvalidResponse invalid) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(Message.assistant(first.content()));
            retry.add(AnalysisPrompt.retryMessage(invalid.getMessage()));
            StudioReply second = call(retry);
            try {
                return parser.parse(second.content());
            } catch (ExpenseGuidanceParser.InvalidResponse again) {
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
}
