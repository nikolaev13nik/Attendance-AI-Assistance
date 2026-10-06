package att.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@Getter
@Setter
public class AssistanceConfiguration {
    @Value("${att.ai.analysis.history-months:6}")
    private int historyMonths;

    @Value("${att.ai.notification.from}")
    private String notificationFrom;

    @Value("${att.ai.notification.enabled:true}")
    private boolean notificationEnabled;

    @Value("${att.ai.llm.provider:stub}")
    private String llmProvider;

    @Value("${att.ai.llm.model:claude-sonnet-5}")
    private String llmModel;

    @Value("${att.ai.llm.max-tokens:4000}")
    private long llmMaxTokens;

    @Value("${att.ai.llm.timeout-seconds:40}")
    private int llmTimeoutSeconds;

    @Value("${att.ai.llm.max-retries:1}")
    private int llmMaxRetries;
}
