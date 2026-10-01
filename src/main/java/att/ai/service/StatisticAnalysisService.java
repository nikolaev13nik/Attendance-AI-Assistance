package att.ai.service;

import org.springframework.stereotype.Service;

import att.ai.llm.LlmAnalysisClient;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import lombok.RequiredArgsConstructor;

/**
 * Single step between the Kafka consumer and the notification side: renders the prompt, asks the model, and
 * hands back the analysis text. Keeping it separate means the consumer stays a thin adapter over the
 * transport and the pipeline can be tested without a broker.
 */
@Service
@RequiredArgsConstructor
public class StatisticAnalysisService {

    private final PromptComposer promptComposer;
    private final LlmAnalysisClient llmAnalysisClient;

    public String analyse(UserStatisticAnalysisRequestEvent event) {
        return llmAnalysisClient.analyse(promptComposer.compose(event));
    }
}
