package att.ai.messaging.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.Set;
import java.util.function.Consumer;

import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.service.StatisticAnalysisService;
import co.il.attendance.logging.LogContextScope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

import static co.il.attendance.logging.LogContext.TARGET_USER_ID;
import static co.il.attendance.logging.LogContext.TENANT_ID;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Configuration
@RequiredArgsConstructor
public class StatisticAnalysisConsumer {

    private static final Logger log = LoggerFactory.getLogger(StatisticAnalysisConsumer.class);

    private final Validator validator;
    private final StatisticAnalysisService statisticAnalysisService;

    @Bean
    public Consumer<Message<UserStatisticAnalysisRequestEvent>> userStatisticAnalysis() {
        return this::userStatisticAnalysisHandler;
    }

    private void userStatisticAnalysisHandler(Message<UserStatisticAnalysisRequestEvent> message) {
        UserStatisticAnalysisRequestEvent event = message.getPayload();
        try (LogContextScope ignored = LogContextScope.open()
                .with(TENANT_ID, event.getTenantId())
                .with(TARGET_USER_ID, event.getUserId())) {
            validate(event);
            log.info("Received analysis request for tenant:{} user:{} with {} month(s) of history, recipient present={}",
                    event.getTenantId(), event.getUserId(), entryCount(event), hasRecipient(event));

            statisticAnalysisService.execute(AnalysisContext.builder().event(event).build());
        }
    }

    private void validate(UserStatisticAnalysisRequestEvent event) {
        Set<ConstraintViolation<UserStatisticAnalysisRequestEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private boolean hasRecipient(UserStatisticAnalysisRequestEvent event) {
        return event.getReportEmail() != null && isFalse(event.getReportEmail().isBlank());
    }

    private int entryCount(UserStatisticAnalysisRequestEvent event) {
        return event.getEntries() == null ? 0 : event.getEntries().size();
    }
}
