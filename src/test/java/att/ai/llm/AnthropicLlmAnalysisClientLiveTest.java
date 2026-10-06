package att.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

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

    private static List<String> monthTokens(YearMonth month) {
        return List.of(month.toString(),
            month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
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

            () -> assertTrue(monthTokens(YearMonth.parse(OLDEST_TRIMMED_MONTH)).stream()
                    .noneMatch(analysis::contains),
                "Reason: " + OLDEST_TRIMMED_MONTH + " is trimmed by history-months=6, so neither it "
                    + "nor its month name can appear in an honest answer - seeing either means the "
                    + "window was not applied"),

            () -> assertTrue(sixMonthWindow().stream()
                    .map(UserStatisticHistoryEntryDto::getYearMonth)
                    .flatMap(month -> monthTokens(month).stream())
                    .anyMatch(analysis::contains),
                "Reason: at least one in-window month should be named, which shows the table was read "
                    + "rather than ignored. Both forms count, because the model writes 'August' where "
                    + "the prompt said '2023-08', and either proves it read the row"),

            () -> assertTrue(analysis.length() < MAX_TOKENS,
                "Reason: a bounded answer shows max-tokens and the brevity instruction are being "
                    + "honoured - an essay here would mean the format contract is not landing"),

            () -> assertFalse(analysis.contains("**") || analysis.contains("##"),
                "Reason: EmailContentHandler HTML-escapes every line, so markdown would be shown to "
                    + "the reader literally. This is the output-format contract being verified"));
    }
}
