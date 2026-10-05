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

/**
 * Pins that the env vars helm/aiassistance sets actually reach the @Value placeholders on
 * AssistanceConfiguration - a silent miss here would leave a chart value looking configured while the
 * service kept its default.
 * <p>
 * Two independent mechanisms resolve an env var for a @Value placeholder, and the chart relies on the
 * second: SystemEnvironmentPropertySource maps separators, which matches HISTORY_MONTHS, and Boot's
 * ConfigurationPropertySourcesPropertySource - attached to the environment by
 * SpringApplication.prepareEnvironment, and by this test's runner - additionally applies relaxed binding,
 * which drops the dash and so matches HISTORYMONTHS. Both names work; the chart uses the latter.
 */
@DisplayName("AssistanceConfiguration: resolves the @Value placeholders from the Helm env vars")
class AssistanceConfigurationTest {

    /**
     * Exactly what helm/aiassistance/templates/configmap.yaml emits.
     */
    private static final String CHART_ENV_VAR = "ATT_AI_ANALYSIS_HISTORYMONTHS";

    /**
     * The separator-mapped spelling, which resolves through the plain property source.
     */
    private static final String UNDERSCORED_ENV_VAR = "ATT_AI_ANALYSIS_HISTORY_MONTHS";

    private static final String SENDER_ENV_VAR = "ATT_AI_NOTIFICATION_FROM";

    /**
     * A context whose 'systemEnvironment' source carries the given variables, as a deployed pod's would.
     */
    private ApplicationContextRunner runnerWithEnv(Map<String, Object> environmentVariables) {
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    environmentVariables)))
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AssistanceConfiguration.class);
    }

    /**
     * The sender has no default, so every context needs it before anything else can be asserted.
     */
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
