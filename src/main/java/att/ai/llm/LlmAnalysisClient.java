package att.ai.llm;

/**
 * Boundary between this service and whichever LLM eventually produces the trend analysis. Kept to one
 * method so the real implementation (HTTP client, credentials, retries, token accounting) can be dropped
 * in without the consumer or the notification side knowing anything changed.
 */
public interface LlmAnalysisClient {

    /**
     * @param prompt the rendered statistic history, as built by {@code PromptComposer}
     * @return the analysis text to put in the email body
     */
    String analyse(String prompt);
}
