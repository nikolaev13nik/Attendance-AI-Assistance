package att.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@Getter
@Setter
public class AssistanceConfiguration {

    @Value("${att.ai.analysis.history-months:6}")
    private int historyMonths;

    @Value("${att.ai.notification.from}")
    private String notificationFrom;

    @Value("${att.ai.notification.enabled:true}")
    private boolean notificationEnabled;
}
