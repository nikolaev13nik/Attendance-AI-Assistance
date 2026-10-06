package att.ai.llm;

import com.anthropic.client.AnthropicClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import att.ai.BaseAiAssistanceTest;
import att.ai.config.AssistanceConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

@DisplayName("LlmAnalysisClient selection: free by default, paid only when explicitly asked for")
class LlmAnalysisClientSelectionTest extends BaseAiAssistanceTest {
    @Autowired
    private LlmAnalysisClient llmAnalysisClient;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AssistanceConfiguration.class,
                StubLlmAnalysisClient.class,
                AnthropicLlmAnalysisClient.class,
                MockAnthropicClient.class)

            .withPropertyValues("att.ai.notification.from=no-reply@attendance.test");
    }

    @Test
    @DisplayName("the running test context uses the stub, so the suite cannot spend credits")
    void defaultContext_usesTheStubClientTest() {
        assertInstanceOf(StubLlmAnalysisClient.class, llmAnalysisClient,
            "Reason: src/test/resources/application.properties pins att.ai.llm.provider=stub. If this "
                + "ever resolves to the Anthropic client, every `mvn test` and every CI build starts "
                + "charging the prepaid credits");
    }

    @Test
    @DisplayName("with no provider property at all the stub still wins")
    void unsetProvider_fallsBackToTheStubTest() {
        runner().run(context -> assertThat(context)
            .hasSingleBean(StubLlmAnalysisClient.class)
            .doesNotHaveBean(AnthropicLlmAnalysisClient.class));
    }

    @Test
    @DisplayName("att.ai.llm.provider=anthropic swaps the clients over, leaving exactly one")
    void anthropicProvider_selectsTheRealClientTest() {
        runner().withPropertyValues("att.ai.llm.provider=anthropic")
            .run(context -> assertThat(context)
                .hasSingleBean(AnthropicLlmAnalysisClient.class)
                .doesNotHaveBean(StubLlmAnalysisClient.class));
    }

    @Configuration
    static class MockAnthropicClient {
        @Bean
        AnthropicClient anthropicClient() {
            return mock(AnthropicClient.class);
        }
    }
}
