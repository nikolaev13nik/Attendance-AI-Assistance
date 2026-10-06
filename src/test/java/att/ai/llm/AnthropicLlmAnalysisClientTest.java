package att.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import att.ai.config.AssistanceConfiguration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnthropicLlmAnalysisClient: one paid call per prompt, and failures split by whether a retry could help")
class AnthropicLlmAnalysisClientTest {
    private static final String PROMPT = "Month    Work days\n2024-01         22";
    private static final String MODEL = "claude-sonnet-5";
    private static final long MAX_TOKENS = 4000L;

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    private AssistanceConfiguration configuration;
    private AnthropicLlmAnalysisClient client;

    @BeforeEach
    void setUp() {
        configuration = new AssistanceConfiguration();
        configuration.setLlmModel(MODEL);
        configuration.setLlmMaxTokens(MAX_TOKENS);
        configuration.setLlmTimeoutSeconds(40);
        configuration.setLlmMaxRetries(1);
        client = new AnthropicLlmAnalysisClient(anthropicClient, configuration);
    }

    private Message message(StopReason stopReason, String... texts) {
        Message response = mock(Message.class);
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(400L);
        when(usage.outputTokens()).thenReturn(300L);
        when(response.usage()).thenReturn(usage);
        when(response.stopReason()).thenReturn(Optional.ofNullable(stopReason));

        List<ContentBlock> blocks = Arrays.stream(texts).map(text -> {
            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(text);
            ContentBlock block = mock(ContentBlock.class);
            when(block.text()).thenReturn(Optional.of(textBlock));
            return block;
        }).toList();
        when(response.content()).thenReturn(blocks);
        return response;
    }

    private void respondWith(Message response) {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class), any())).thenReturn(response);
    }

    private void failWith(RuntimeException failure) {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class), any())).thenThrow(failure);
    }

    private AnthropicServiceException httpFailure(int status) {
        AnthropicServiceException failure = mock(AnthropicServiceException.class);
        when(failure.statusCode()).thenReturn(status);
        return failure;
    }

    @Test
    @DisplayName("the model's text comes back joined, from exactly one request")
    void analyse_returnsTheModelTextAndCallsTheApiOnceTest() {
        respondWith(message(StopReason.END_TURN, "Workload is steady.", "Overtime is occasional."));

        String analysis = client.analyse(PROMPT);

        assertEquals("Workload is steady.\nOvertime is occasional.", analysis,
            "Reason: the API may split prose across several text blocks, so they must be joined "
                + "rather than only the first one read");
        verify(messageService, times(1)).create(any(MessageCreateParams.class), any());
    }

    @Test
    @DisplayName("the request carries the configured model, the token ceiling, the format contract and the prompt")
    void analyse_sendsTheConfiguredRequestTest() {
        respondWith(message(StopReason.END_TURN, "Workload is steady."));

        client.analyse(PROMPT);

        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messageService).create(sent.capture(), any());

        String request = sent.getValue().toString();
        assertAll(
            () -> assertTrue(request.contains(MODEL),
                "Reason: the model id must come from att.ai.llm.model, not from an SDK default"),
            () -> assertTrue(request.contains(String.valueOf(MAX_TOKENS)),
                "Reason: the token ceiling is the per-call spend limit - a request without it is "
                    + "unbounded output"),
            () -> assertTrue(request.contains("No markdown"),
                "Reason: the output-format contract is what stops markdown being HTML-escaped into "
                    + "the reader's inbox"),
            () -> assertTrue(request.contains("2024-01"),
                "Reason: the composed prompt must reach the API unchanged"));
    }

    @Test
    @DisplayName("the same prompt is never paid for twice")
    void analyse_theSamePromptIsNotPaidForTwiceTest() {
        respondWith(message(StopReason.END_TURN, "Workload is steady."));

        String first = client.analyse(PROMPT);
        String second = client.analyse(PROMPT);

        assertEquals(first, second,
            "Reason: the analysis is a pure function of the prompt, so a cached answer is correct, "
                + "not stale");
        verify(messageService, times(1)).create(any(MessageCreateParams.class), any());
    }

    @Test
    @DisplayName("a truncated answer is emailed with a notice rather than retried")
    void analyse_truncatedAnswerIsUsedWithANoticeTest() {
        respondWith(message(StopReason.MAX_TOKENS, "Workload is steady but the trend"));

        String analysis = client.analyse(PROMPT);

        assertTrue(analysis.startsWith("Workload is steady but the trend"),
            "Reason: truncated prose is still usable, so it must not be discarded");
        assertTrue(analysis.contains("cut short"),
            "Reason: the reader needs to know why the text stops mid-thought");
        verify(messageService, times(1)).create(any(MessageCreateParams.class), any());
    }

    @Test
    @DisplayName("an empty answer is permanent, not worth retrying")
    void analyse_blankAnswerIsRejectedAsPermanentTest() {
        respondWith(message(StopReason.END_TURN, "   "));

        assertThrows(LlmRequestRejectedException.class, () -> client.analyse(PROMPT),
            "Reason: the prompt is deterministic, so a second identical call is unlikely to produce "
                + "text - and an empty analysis in somebody's inbox is worse than a DLQ entry");
    }

    @Test
    @DisplayName("a refusal goes to the DLQ instead of being retried")
    void analyse_refusalIsRejectedAsPermanentTest() {
        respondWith(message(StopReason.REFUSAL, "Workload is steady."));

        assertThrows(LlmRequestRejectedException.class, () -> client.analyse(PROMPT),
            "Reason: a refusal is a decision about the request, so the identical request will be "
                + "refused again");
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 502, 503, 504, 529})
    @DisplayName("rate limits, overload and server errors stay retryable")
    void analyse_transientHttpFailuresAreRetryableTest(int status) {
        failWith(httpFailure(status));

        assertThrows(LlmAnalysisException.class, () -> client.analyse(PROMPT),
            "Reason: HTTP " + status + " is the API having a bad moment, and this type is absent from "
                + "retryable-exceptions in async-messages.yml so it keeps the bounded retry");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 404, 413, 422})
    @DisplayName("bad keys, exhausted credit and unknown models go straight to the DLQ")
    void analyse_permanentHttpFailuresGoStraightToTheDlqTest(int status) {
        failWith(httpFailure(status));

        assertThrows(LlmRequestRejectedException.class, () -> client.analyse(PROMPT),
            "Reason: HTTP " + status + " fails identically on attempt two, so retrying only spends "
                + "credit. This exception type is the one named in async-messages.yml, which is what "
                + "makes the first attempt the last one");
    }

    @Test
    @DisplayName("a connection failure is retryable")
    void analyse_networkFailureIsRetryableTest() {
        failWith(mock(AnthropicException.class));

        assertThrows(LlmAnalysisException.class, () -> client.analyse(PROMPT),
            "Reason: a dropped connection or a read timeout never reached a decision, so a second "
                + "attempt is worth one request");
    }

    @Test
    @DisplayName("a failure is not cached, so a retry really does retry")
    void analyse_failuresAreNotCachedTest() {
        failWith(httpFailure(503));

        assertThrows(LlmAnalysisException.class, () -> client.analyse(PROMPT));
        assertThrows(LlmAnalysisException.class, () -> client.analyse(PROMPT),
            "Reason: only successful analyses are cached - caching a failure would make the bounded "
                + "retry silently do nothing");
        verify(messageService, times(2)).create(any(MessageCreateParams.class), any());
    }
}
