package att.ai.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.function.Consumer;

import att.ai.BaseAiAssistanceTest;
import att.ai.messaging.consumer.StatisticAnalysisConsumer;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.service.StatisticAnalysisService;
import co.il.attendance.logging.LogContext;
import jakarta.mail.internet.MimeMessage;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("userStatisticAnalysis consumer: turns an analysis request into one email to the requester")
class StatisticAnalysisConsumerTest extends BaseAiAssistanceTest {
    @Autowired
    private Validator validator;

    @Autowired
    private StatisticAnalysisService statisticAnalysisService;

    private Consumer<Message<UserStatisticAnalysisRequestEvent>> analysisConsumer() {
        return new StatisticAnalysisConsumer(validator, statisticAnalysisService).userStatisticAnalysis();
    }

    private void consume(UserStatisticAnalysisRequestEvent event) {
        analysisConsumer().accept(MessageBuilder.withPayload(event).build());
    }

    @Test
    @DisplayName("a valid request is analysed and emailed, carrying the months from the message")
    void consume_validRequestEmailsTheAnalysisTest() throws Exception {
        consume(fullEvent());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertEquals("Work-time analysis for Anna Smith (2023-08 - 2024-01)", sent.getSubject(),
                "Reason: the period comes from the trimmed window, not from the raw 7 entries sent");
        String body = bodyOf(sent);
        assertTrue(body.contains("Hello John Doe,"),
                "Reason: the requester fields TimeTracking resolved must reach the email");
        assertTrue(body.contains("<td>2024-01</td>") && body.contains("<td>2023-08</td>"),
                "Reason: the window's months must be tabled in the body");
        assertTrue(body.contains("Placeholder analysis"),
            "Reason: att.ai.llm.provider defaults to 'stub', so the stub produces the prose here - "
                + "which is what keeps mvn test and CI from spending prepaid credits");
    }

    @Test
    @DisplayName("a request with no recipient is acknowledged without sending - it must not reach the DLQ")
    void consume_requestWithoutRecipientSendsNothingTest() {
        consume(eventBuilder().reportEmail(null).entries(sevenMonthHistory()).build());

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a request with no history still notifies, saying there was nothing to analyse")
    void consume_requestWithoutHistoryStillNotifiesTest() throws Exception {
        consume(eventBuilder().build());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("Work-time analysis for Anna Smith", captor.getValue().getSubject(),
                "Reason: with no months there is no period to name");
        assertTrue(bodyOf(captor.getValue()).contains("No monthly statistics were recorded"),
                "Reason: the requester asked for a report and deserves an answer, even an empty one");
    }

    @Test
    @DisplayName("a request whose entries field is null is consumed, not NPE'd in the log line")
    void consume_nullEntriesIsConsumedTest() throws Exception {
        consume(eventBuilder().entries(null).build());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("Work-time analysis for Anna Smith", captor.getValue().getSubject(),
                "Reason: a null history is an empty one, so the requester still gets an answer");
    }

    @Test
    @DisplayName("a malformed request (missing userId) is rejected and never emailed")
    void consume_malformedRequestIsRejectedTest() {
        UserStatisticAnalysisRequestEvent malformed = UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID).reportEmail(REPORT_EMAIL).build();

        assertThrows(ConstraintViolationException.class, () -> consume(malformed),
                "Reason: a message without the mandatory userId must be rejected, not silently dropped");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("every line written while handling a request carries the event's tenant and user, and none is left behind")
    void consume_logsWithEventContextTest(CapturedOutput output) {
        consume(fullEvent());

        String line = output.getOut().lines().filter(l -> l.contains("Received analysis request")).findFirst()
                .orElse("");
        assertTrue(line.contains("tenant=" + TENANT_ID + " target=" + USER_ID + "]"),
                "Reason: a consumer has no HTTP request, so the event names the tenant and user, line: " + line);
        assertTrue(line.contains("recipient present=true"), "Reason: the line says whether anyone gets the email");
        assertFalse(output.getOut().contains(REPORT_EMAIL), "Reason: the recipient's email is personal data");
        assertFalse(output.getOut().contains(REPORT_LAST_NAME), "Reason: the recipient's name is personal data");
        assertNull(MDC.get(LogContext.TENANT_ID), "Reason: the next message on this thread must not inherit the tenant");
        assertNull(MDC.get(LogContext.TARGET_USER_ID), "Reason: the next message on this thread must not inherit the user");
    }
}
