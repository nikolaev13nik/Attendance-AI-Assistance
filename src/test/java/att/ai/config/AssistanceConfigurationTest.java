package att.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AssistanceConfiguration: resolves the @Value placeholders from the Helm env vars")
class AssistanceConfigurationTest {
    private static final String CHART_ENV_VAR = "ATT_AI_ANALYSIS_HISTORYMONTHS";

    private static final String UNDERSCORED_ENV_VAR = "ATT_AI_ANALYSIS_HISTORY_MONTHS";

    private static final String SENDER_ENV_VAR = "ATT_AI_NOTIFICATION_FROM";

    private ApplicationContextRunner runnerWithEnv(Map<String, Object> environmentVariables) {
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    environmentVariables)))
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AssistanceConfiguration.class);
    }

    private Map<String, Object> envWithSender(String... nameValuePairs) {
        Map<String, Object> environment = new HashMap<>();
        environment.put(SENDER_ENV_VAR, "no-reply@attendance.test");
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            environment.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return environment;
    }

    private int historyMonthsFrom(Map<String, Object> environment) {
        int[] resolved = new int[1];
        runnerWithEnv(environment).run(context ->
            resolved[0] = context.getBean(AssistanceConfiguration.class).getHistoryMonths());
        return resolved[0];
    }

    @Test
    @DisplayName("the chart's own env var name reaches att.ai.analysis.history-months")
    void historyMonths_bindsFromTheChartEnvVarTest() {
        assertEquals(7, historyMonthsFrom(envWithSender(CHART_ENV_VAR, "7")),
            "Reason: this is the name the ConfigMap sets, so if it stops resolving the chart's "
                + "historyMonths value silently does nothing");
    }

    @Test
    @DisplayName("the separator-mapped spelling resolves too, so either name is safe to set")
    void historyMonths_bindsFromTheUnderscoredEnvVarTest() {
        assertEquals(7, historyMonthsFrom(envWithSender(UNDERSCORED_ENV_VAR, "7")),
            "Reason: SystemEnvironmentPropertySource maps history-months to HISTORY_MONTHS without "
                + "needing relaxed binding, so both spellings reach the same placeholder");
    }

    @Test
    @DisplayName("defaults hold when only the sender is configured")
    void defaults_applyWhenNothingElseIsSetTest() {
        runnerWithEnv(envWithSender()).run(context -> {
            AssistanceConfiguration configuration = context.getBean(AssistanceConfiguration.class);
            assertEquals(6, configuration.getHistoryMonths(),
                "Reason: 6 months is the documented default when the chart sets nothing");
            assertTrue(configuration.isNotificationEnabled(),
                "Reason: notifications are on unless MAIL_ENABLED says otherwise");
            assertEquals("stub", configuration.getLlmProvider(),
                "Reason: the free client must be the default everywhere, so that forgetting to set "
                    + "the provider can never start spending credits");
            assertEquals("claude-sonnet-5", configuration.getLlmModel(),
                "Reason: the model id is a default, not a required setting, so a chart that sets "
                    + "nothing still produces a valid request");
            assertEquals(4000L, configuration.getLlmMaxTokens(),
                "Reason: the per-call spend ceiling must exist without the chart configuring it");
            assertEquals(40, configuration.getLlmTimeoutSeconds(),
                "Reason: an unset timeout would fall back to the SDK's 10 minutes, which is what "
                    + "turns a hung call into a Kafka rebalance loop");
            assertEquals(1, configuration.getLlmMaxRetries(),
                "Reason: 1 SDK retry is the bounded default the async-messages.yml arithmetic assumes");
        });
    }

    @Test
    @DisplayName("the chart's own env var names reach every att.ai.llm placeholder")
    void llmSettings_bindFromTheChartEnvVarsTest() {
        runnerWithEnv(envWithSender(
            "ATT_AI_LLM_PROVIDER", "anthropic",
            "ATT_AI_LLM_MODEL", "claude-haiku-4-5",
            "ATT_AI_LLM_MAXTOKENS", "1234",
            "ATT_AI_LLM_TIMEOUTSECONDS", "15",
            "ATT_AI_LLM_MAXRETRIES", "0")).run(context -> {
            AssistanceConfiguration configuration = context.getBean(AssistanceConfiguration.class);
            assertEquals("anthropic", configuration.getLlmProvider(),
                "Reason: this is the dash-dropped spelling the ConfigMap emits, and it is the only "
                    + "thing that switches the service onto the paid client");
            assertEquals("claude-haiku-4-5", configuration.getLlmModel(),
                "Reason: changing the model must be a chart value, not a code change");
            assertEquals(1234L, configuration.getLlmMaxTokens(),
                "Reason: the spend ceiling has to be tunable per environment without a rebuild");
            assertEquals(15, configuration.getLlmTimeoutSeconds(),
                "Reason: the timeout feeds the max.poll.interval.ms arithmetic, so an operator "
                    + "must be able to lower it from the chart");
            assertEquals(0, configuration.getLlmMaxRetries(),
                "Reason: 0 is a meaningful value - one HTTP attempt only - so it must survive "
                    + "binding rather than being treated as unset");
        });
    }

    @Test
    @DisplayName("a missing sender fails the context rather than mailing from null")
    void missingSender_failsStartupTest() {
        runnerWithEnv(Map.of()).run(context -> assertNotNull(context.getStartupFailure(),
            "Reason: att.ai.notification.from has no default on purpose - an unresolvable placeholder "
                + "must stop the context, not leave the sender null"));
    }
}
