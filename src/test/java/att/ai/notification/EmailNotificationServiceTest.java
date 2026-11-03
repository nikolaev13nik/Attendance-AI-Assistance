package att.ai.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import att.ai.BaseAiAssistanceTest;
import att.ai.config.AssistanceConfiguration;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("EmailNotificationService: sends the analysis to the requester, or explains why it did not")
class EmailNotificationServiceTest extends BaseAiAssistanceTest {

    @Autowired
    private EmailNotificationService emailNotificationService;

    /**
     * The same bean the service reads, so a test can retune it - and must put it back afterwards.
     */
    @Autowired
    private AssistanceConfiguration configuration;

    private MimeMessage captureSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a complete context produces one HTML message addressed to reportEmail")
    void execute_sendsHtmlMessageToReportEmailTest() throws Exception {
        emailNotificationService.execute(analysedContext());

        MimeMessage sent = captureSentMessage();
        assertEquals(REPORT_EMAIL, sent.getRecipients(RecipientType.TO)[0].toString(),
                "Reason: the recipient is the address TimeTracking resolved from Accounting");
        assertEquals(FROM_ADDRESS, sent.getFrom()[0].toString());
        assertEquals("Work-time analysis for Anna Smith (2023-08 - 2024-01)", sent.getSubject());
        // MimeMessage writes its MIME headers in saveChanges(), which the real Transport.send performs and
        // the mocked sender does not - so the header is materialised here before being asserted on
        sent.saveChanges();
        assertTrue(sent.getContentType().contains("text/html"),
                "Reason: the body is HTML, so the header must say so or clients show raw markup");
        assertTrue(bodyOf(sent).contains("Hello John Doe,"));
    }

    @Test
    @DisplayName("no recipient on the event - the analysis cannot be delivered, and nothing is thrown")
    void canDeliver_blankReportEmailIsFalseTest() {
        UserStatisticAnalysisRequestEvent noRecipient = UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID).userId(USER_ID).reportEmail("  ").entries(sevenMonthHistory()).build();

        // throwing here would push the message to the DLQ, alerting on something no retry can fix
        assertFalse(emailNotificationService.canDeliver(analysedContext(noRecipient, sixMonthWindow(), ANALYSIS_TEXT)),
                "Reason: with nobody to email the caller must not pay for an analysis");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a missing reportEmail field is treated the same as a blank one")
    void canDeliver_nullReportEmailIsFalseTest() {
        UserStatisticAnalysisRequestEvent noRecipient = UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID).userId(USER_ID).entries(sevenMonthHistory()).build();

        assertFalse(emailNotificationService.canDeliver(analysedContext(noRecipient, sixMonthWindow(), ANALYSIS_TEXT)));
    }

    @Test
    @DisplayName("with notifications disabled the analysis cannot be delivered")
    void canDeliver_disabledIsFalseTest() {
        configuration.setNotificationEnabled(false);
        try {
            assertFalse(emailNotificationService.canDeliver(analysedContext()),
                    "Reason: an analysis that cannot be mailed is not worth a paid call");
        } finally {
            configuration.setNotificationEnabled(true);
        }
    }

    @Test
    @DisplayName("an unusable from address surfaces as EmailDeliveryException, which the binder retries")
    void execute_unbuildableMessageThrowsDeliveryExceptionTest() {
        configuration.setNotificationFrom("not a valid address");
        try {
            assertThrows(EmailDeliveryException.class,
                    () -> emailNotificationService.execute(
                            analysedContext(fullEvent(), List.of(), ANALYSIS_TEXT)),
                    "Reason: a build failure must be an unchecked exception the binder can retry, not a "
                            + "checked MessagingException leaking out of the consumer");
            verify(mailSender, never()).send(any(MimeMessage.class));
        } finally {
            configuration.setNotificationFrom(FROM_ADDRESS);
        }
    }
}
