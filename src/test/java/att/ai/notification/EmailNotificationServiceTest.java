package att.ai.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import att.ai.BaseAiAssistanceTest;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.service.StatisticAnalysis;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("EmailNotificationService: sends the analysis to the requester, or explains why it did not")
class EmailNotificationServiceTest extends BaseAiAssistanceTest {

    @Autowired
    private EmailNotificationService emailNotificationService;

    private StatisticAnalysis analysis() {
        return new StatisticAnalysis(sixMonthWindow(), "Workload is steady.");
    }

    private MimeMessage captureSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a complete event produces one HTML message addressed to reportEmail")
    void notifyRequester_sendsHtmlMessageToReportEmailTest() throws Exception {
        emailNotificationService.notifyRequester(fullEvent(), analysis());

        MimeMessage sent = captureSentMessage();
        assertEquals(REPORT_EMAIL, sent.getRecipients(RecipientType.TO)[0].toString(),
                "Reason: the recipient is the address TimeTracking resolved from Accounting");
        assertEquals(FROM_ADDRESS, sent.getFrom()[0].toString());
        assertEquals("Work-time analysis for user 7 (2023-08 - 2024-01)", sent.getSubject());
        // MimeMessage writes its MIME headers in saveChanges(), which the real Transport.send performs and
        // the mocked sender does not - so the header is materialised here before being asserted on
        sent.saveChanges();
        assertTrue(sent.getContentType().contains("text/html"),
                "Reason: the body is HTML, so the header must say so or clients show raw markup");
        assertTrue(bodyOf(sent).contains("Hello John Doe,"));
    }

    @Test
    @DisplayName("no recipient on the event - nothing is sent and nothing is thrown")
    void notifyRequester_blankReportEmailSkipsSendingTest() {
        UserStatisticAnalysisRequestEvent noRecipient = UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID).userId(USER_ID).reportEmail("  ").entries(sevenMonthHistory()).build();

        emailNotificationService.notifyRequester(noRecipient, analysis());

        // throwing here would push the message to the DLQ, alerting on something no retry can fix
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a missing reportEmail field is treated the same as a blank one")
    void notifyRequester_nullReportEmailSkipsSendingTest() {
        UserStatisticAnalysisRequestEvent noRecipient = UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID).userId(USER_ID).entries(sevenMonthHistory()).build();

        emailNotificationService.notifyRequester(noRecipient, analysis());

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("with notifications disabled the pipeline runs but sends nothing")
    void notifyRequester_disabledSendsNothingTest() {
        ReflectionTestUtils.setField(emailNotificationService, "enabled", false);
        try {
            emailNotificationService.notifyRequester(fullEvent(), analysis());
            verify(mailSender, never()).send(any(MimeMessage.class));
        } finally {
            ReflectionTestUtils.setField(emailNotificationService, "enabled", true);
        }
    }

    @Test
    @DisplayName("an unusable from address surfaces as EmailDeliveryException, which the binder retries")
    void notifyRequester_unbuildableMessageThrowsDeliveryExceptionTest() {
        ReflectionTestUtils.setField(emailNotificationService, "from", "not a valid address");
        try {
            assertThrows(EmailDeliveryException.class,
                    () -> emailNotificationService.notifyRequester(fullEvent(),
                            new StatisticAnalysis(List.of(), "Workload is steady.")),
                    "Reason: a build failure must be an unchecked exception the binder can retry, not a "
                            + "checked MessagingException leaking out of the consumer");
            verify(mailSender, never()).send(any(MimeMessage.class));
        } finally {
            ReflectionTestUtils.setField(emailNotificationService, "from", FROM_ADDRESS);
        }
    }
}
