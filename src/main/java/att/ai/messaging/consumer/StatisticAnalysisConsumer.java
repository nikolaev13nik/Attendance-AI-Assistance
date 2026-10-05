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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

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
        validate(event);
        log.info("Received analysis request for tenant:{} user:{} with {} month(s) of history, requested by {} {} <{}>",
            event.getTenantId(), event.getUserId(), entryCount(event),
                event.getReportUserName(), event.getReportUserLastName(), event.getReportEmail());

        statisticAnalysisService.execute(AnalysisContext.builder().event(event).build());
    }

    private void validate(UserStatisticAnalysisRequestEvent event) {
        Set<ConstraintViolation<UserStatisticAnalysisRequestEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private int entryCount(UserStatisticAnalysisRequestEvent event) {
        return event.getEntries() == null ? 0 : event.getEntries().size();
    }
}
