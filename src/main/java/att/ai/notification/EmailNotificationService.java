package att.ai.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.service.StatisticAnalysis;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

/**
 * Sends the finished analysis to the address the Kafka message carries - the report requester resolved by
 * TimeTracking from Attendance-Accounting.
 */
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final EmailContentBuilder contentBuilder;

    @Value("${att.ai.notification.from}")
    private String from;

    @Value("${att.ai.notification.enabled:true}")
    private boolean enabled;

    public void notifyRequester(UserStatisticAnalysisRequestEvent event, StatisticAnalysis analysis) {
        if (!enabled) {
            log.info("Mail disabled, not sending the analysis for tenant:{} user:{}",
                    event.getTenantId(), event.getUserId());
            return;
        }
        // A message with no recipient is incomplete data, not a malformed message: throwing here would send
        // it to the DLQ and alert on something no retry can fix, so it is logged and dropped instead.
        if (event.getReportEmail() == null || event.getReportEmail().isBlank()) {
            log.warn("No reportEmail on the analysis request for tenant:{} user:{}, nothing to notify",
                    event.getTenantId(), event.getUserId());
            return;
        }

        mailSender.send(buildMessage(event, analysis));
        log.info("Analysis for tenant :{} user:{} sent to {}",
                event.getTenantId(), event.getUserId(), event.getReportEmail());
    }

    private MimeMessage buildMessage(UserStatisticAnalysisRequestEvent event, StatisticAnalysis analysis) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(event.getReportEmail());
            helper.setSubject(contentBuilder.subject(event, analysis));
            // true = the body is HTML
            helper.setText(contentBuilder.htmlBody(event, analysis), true);
        } catch (MessagingException e) {
            throw new EmailDeliveryException("Could not build the analysis email for tenant:%s user:%s"
                    .formatted(event.getTenantId(), event.getUserId()), e);
        }
        return message;
    }
}
