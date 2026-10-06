package att.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import att.ai.config.AssistanceConfiguration;

@Configuration
@ConditionalOnProperty(name = "att.ai.llm.provider", havingValue = "anthropic")
public class AnthropicClientConfiguration {
    @Bean
    public AnthropicClient anthropicClient(AssistanceConfiguration configuration) {
        return AnthropicOkHttpClient.builder()
            .fromEnv()
            .maxRetries(configuration.getLlmMaxRetries())
            .timeout(Duration.ofSeconds(configuration.getLlmTimeoutSeconds()))
            .build();
    }
}
