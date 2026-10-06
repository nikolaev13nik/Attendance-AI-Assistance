package att.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import att.ai.AnalysisFixtures;
import att.ai.config.AssistanceConfiguration;
import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import att.ai.service.PromptComposer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("llm")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@DisplayName("AnthropicLlmAnalysisClient against the real API: the production prompt comes back as usable prose")
class AnthropicLlmAnalysisClientLiveTest extends AnalysisFixtures {
    private static final String MODEL = "claude-sonnet-5";
    private static final long MAX_TOKENS = 4000L;
    private static final int TIMEOUT_SECONDS = 60;

    private AssistanceConfiguration configuration() {
        AssistanceConfiguration configuration = new AssistanceConfiguration();
        configuration.setHistoryMonths(6);
        configuration.setLlmModel(MODEL);
        configuration.setLlmMaxTokens(MAX_TOKENS);
        configuration.setLlmTimeoutSeconds(TIMEOUT_SECONDS);
        configuration.setLlmMaxRetries(1);
        return configuration;
    }

    private String productionPrompt(AssistanceConfiguration configuration) {
        PromptComposer composer = new PromptComposer(configuration);
        AnalysisContext context = contextOf(eventBuilder().entries(sevenMonthHistory()).build());
        composer.applyHistoryWindow(context);
        composer.composePrompt(context);
        return context.getPrompt();
    }

    @Test
    @DisplayName("a real call returns prose about the months it was actually shown")
    void analyse_realCallReturnsUsableProseTest() {
        AssistanceConfiguration configuration = configuration();
        String prompt = productionPrompt(configuration);

        AnthropicClient anthropic = AnthropicOkHttpClient.builder()
            .fromEnv()
            .maxRetries(configuration.getLlmMaxRetries())
            .timeout(Duration.ofSeconds(configuration.getLlmTimeoutSeconds()))
            .build();

        String analysis = new AnthropicLlmAnalysisClient(anthropic, configuration).analyse(prompt);

        System.out.println("---------- prompt sent ----------");
        System.out.println(prompt);
        System.out.println("---------- analysis received ----------");
        System.out.println(analysis);
        System.out.println("---------------------------------------");

        assertAll(
            () -> assertFalse(analysis.isBlank(),
                "Reason: an empty analysis would be emailed as an empty body"),

            () -> assertFalse(analysis.contains("Placeholder"),
                "Reason: proves the real client answered rather than the stub - if this fails, the "
                    + "test is not exercising what it claims to"),

            () -> assertFalse(analysis.contains(OLDEST_TRIMMED_MONTH),
                "Reason: " + OLDEST_TRIMMED_MONTH + " is trimmed by history-months=6, so it cannot "
                    + "appear in an honest answer - seeing it means the window was not applied"),

            () -> assertTrue(sixMonthWindow().stream()
                    .map(UserStatisticHistoryEntryDto::getYearMonth)
                    .map(Object::toString)
                    .anyMatch(analysis::contains),
                "Reason: at least one in-window month should be named, which shows the table was read "
                    + "rather than ignored. Deliberately not pinned to one specific month: a good "
                    + "trend summary may well mention only some of them"),

            () -> assertTrue(analysis.length() < MAX_TOKENS,
                "Reason: a bounded answer shows max-tokens and the brevity instruction are being "
                    + "honoured - an essay here would mean the format contract is not landing"),

            () -> assertFalse(analysis.contains("**") || analysis.contains("##"),
                "Reason: EmailContentHandler HTML-escapes every line, so markdown would be shown to "
                    + "the reader literally. This is the output-format contract being verified"));
    }
}
