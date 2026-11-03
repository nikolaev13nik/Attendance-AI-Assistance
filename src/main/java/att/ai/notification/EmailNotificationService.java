package att.ai.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

import att.ai.config.AssistanceConfiguration;
import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailPublisher;
    private final EmailContentHandler emailContentHandler;
    private final AssistanceConfiguration configuration;

    public boolean canDeliver(AnalysisContext context) {
        UserStatisticAnalysisRequestEvent event = context.getEvent();
        if (isFalse(configuration.isNotificationEnabled())) {
            log.info("Mail disabled, no analysis requested for tenant:{} user:{}",
                    event.getTenantId(), event.getUserId());
            return false;
        }
        if (event.getReportEmail() == null || event.getReportEmail().isBlank()) {
            log.warn("No reportEmail on the analysis request for tenant:{} user:{}, no analysis requested",
                    event.getTenantId(), event.getUserId());
            return false;
        }
        return true;
    }

    public void execute(AnalysisContext context) {
        UserStatisticAnalysisRequestEvent event = context.getEvent();
        mailPublisher.send(buildMessage(context));
        log.info("Analysis for tenant:{} user:{} emailed", event.getTenantId(), event.getUserId());
    }

    private MimeMessage buildMessage(AnalysisContext context) {
        UserStatisticAnalysisRequestEvent event = context.getEvent();
        MimeMessage message = mailPublisher.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(configuration.getNotificationFrom());
            helper.setTo(event.getReportEmail());
            helper.setSubject(emailContentHandler.subject(context));
            helper.setText(emailContentHandler.htmlBody(context), true);
        } catch (MessagingException e) {
            throw new EmailDeliveryException("Could not build the analysis email for tenant:%s user:%s"
                    .formatted(event.getTenantId(), event.getUserId()), e);
        }
        return message;
    }
}
