package att.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import att.ai.config.AssistanceConfiguration;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "att.ai.llm.provider", havingValue = "anthropic")
public class AnthropicLlmAnalysisClient implements LlmAnalysisClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmAnalysisClient.class);
    private static final String OUTPUT_FORMAT = """
        Your reply is inserted directly into an HTML email. Answer with two to four short \
        paragraphs of plain prose, each paragraph on a single line, paragraphs separated by one \
        blank line. No markdown, no headings, no bullet lists, no tables, no greeting and no \
        sign-off: the email template supplies those and escapes your text, so any markup would \
        be shown to the reader literally.""";

    private static final String TRUNCATION_NOTICE = "\n\n(The analysis was cut short by the output limit.)";
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(408, 409, 429, 500, 502, 503, 504, 529);
    private static final int CACHE_SIZE = 32;
    private final AnthropicClient anthropicClient;
    private final AssistanceConfiguration configuration;
    private final Map<String, String> recentAnalyses = Collections.synchronizedMap(new BoundedCache());

    @Override
    public String analyse(String prompt) {
        String cached = recentAnalyses.get(prompt);
        if (cached != null) {
            log.info("Reusing the analysis already produced for this prompt - no second paid call");
            return cached;
        }
        String analysis = request(prompt);
        recentAnalyses.put(prompt, analysis);
        return analysis;
    }

    private String request(String prompt) {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(configuration.getLlmModel())
            .maxTokens(configuration.getLlmMaxTokens())

            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(OUTPUT_FORMAT)
            .addUserMessage(prompt)
            .build();

        Message response = executeCall(params);
        logUsage(response);
        return text(response);
    }

    private Message executeCall(MessageCreateParams params) {
        try {
            return anthropicClient.messages().create(params, RequestOptions.builder()
                .timeout(Duration.ofSeconds(configuration.getLlmTimeoutSeconds()))
                .build());
        } catch (AnthropicServiceException e) {
            throw classify(e);
        } catch (AnthropicException e) {
            throw new LlmAnalysisException("The model request did not complete, retrying", e);
        }
    }

    private RuntimeException classify(AnthropicServiceException e) {
        int status = e.statusCode();
        if (TRANSIENT_STATUSES.contains(status) || status >= 500) {
            return new LlmAnalysisException(
                "The model is unavailable (HTTP %d), retrying".formatted(status), e);
        }
        log.error("The model rejected the request permanently with HTTP {} ({}). Retrying cannot help, "
                + "so this message goes to the DLQ - check the ANTHROPIC_API_KEY, the workspace "
                + "credit balance in the Anthropic Console, and att.ai.llm.model (currently {})",
            status, e.errorType().map(Object::toString).orElse("unknown"), configuration.getLlmModel());
        return new LlmRequestRejectedException(
            "The model rejected the request (HTTP %d)".formatted(status), e);
    }

    private void logUsage(Message response) {
        log.info("Model {} used {} input and {} output token(s), stop reason {}",
            configuration.getLlmModel(),
            response.usage().inputTokens(),
            response.usage().outputTokens(),
            response.stopReason().map(Object::toString).orElse("none"));
    }

    private String text(Message response) {
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            log.error("The model declined to answer, so there is nothing to email: {}",
                response.stopDetails());
            throw new LlmRequestRejectedException("The model declined to analyse this history");
        }

        String analysis = response.content().stream()
            .flatMap(block -> block.text().stream())
            .map(textBlock -> textBlock.text())
            .collect(Collectors.joining("\n"))
            .strip();

        if (analysis.isBlank()) {
            throw new LlmRequestRejectedException("The model returned no text to email");
        }

        if (StopReason.MAX_TOKENS.equals(stop)) {
            log.warn("The answer hit att.ai.llm.max-tokens ({}) and is truncated; emailing it with a "
                    + "notice rather than paying for a retry that would truncate identically",
                configuration.getLlmMaxTokens());
            return analysis + TRUNCATION_NOTICE;
        }
        return analysis;
    }

    private static final class BoundedCache extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;

        private BoundedCache() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    }
}
