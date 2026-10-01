package att.ai.messaging.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.Set;
import java.util.function.Consumer;

import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.notification.EmailNotificationService;
import att.ai.service.StatisticAnalysisService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/**
 * Entry point of the service: subscribes to {@code att.user-statistic-ai-analysis}, where TimeTracking
 * publishes a user's recent monthly statistics together with the report requester to notify.
 * <p>
 * The bean name is the binding name: {@code userStatisticAnalysis} is bound to
 * {@code userStatisticAnalysis-in-0} in async-messages.yml, so renaming the method detaches the
 * subscriber.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "att.ai.messaging.analysis.enabled", havingValue = "true", matchIfMissing = true)
public class StatisticAnalysisConsumer {

    private static final Logger log = LoggerFactory.getLogger(StatisticAnalysisConsumer.class);

    private final Validator validator;
    private final StatisticAnalysisService statisticAnalysisService;
    private final EmailNotificationService emailNotificationService;

    @Bean
    public Consumer<Message<UserStatisticAnalysisRequestEvent>> userStatisticAnalysis() {
        return this::userStatisticAnalysisHandler;
    }

    private void userStatisticAnalysisHandler(Message<UserStatisticAnalysisRequestEvent> message) {
        UserStatisticAnalysisRequestEvent event = message.getPayload();
        validate(event);
        log.info("Received analysis request for tenant:{} user:{} with {} month(s) of history, requested by {} {} <{}>",
                event.getTenantId(), event.getUserId(), event.getEntries().size(),
                event.getReportUserName(), event.getReportUserLastName(), event.getReportEmail());

        emailNotificationService.notifyRequester(event, statisticAnalysisService.analyse(event));
    }

    /**
     * Rejects a payload missing its mandatory identity. The binder is configured to treat a
     * {@link ConstraintViolationException} as non-retryable, so such a message goes straight to the DLQ
     * instead of being redelivered forever.
     */
    private void validate(UserStatisticAnalysisRequestEvent event) {
        Set<ConstraintViolation<UserStatisticAnalysisRequestEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
