package att.ai;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.YearMonth;
import java.util.List;

import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import jakarta.mail.internet.MimeMessage;

import static org.mockito.Mockito.when;

/**
 * Boots the real application context without a web server and without Kafka (see
 * src/test/resources/application.properties), and replaces the one external dependency - SMTP - with a mock.
 * Fixtures live here so every test describes the same seven-month history.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class BaseAiAssistanceTest {

    protected static final Integer TENANT_ID = 2;
    protected static final Integer USER_ID = 7;
    protected static final String REPORT_FIRST_NAME = "John";
    protected static final String REPORT_LAST_NAME = "Doe";
    protected static final String REPORT_EMAIL = "john.doe@example.com";
    protected static final String FROM_ADDRESS = "no-reply@attendance.test";

    protected static final String TARGET_MONTH = "2024-01";
    protected static final String OLDEST_KEPT_MONTH = "2023-08";
    protected static final String OLDEST_TRIMMED_MONTH = "2023-07";

    @MockitoBean
    protected JavaMailSender mailSender;

    protected static UserStatisticHistoryEntryDto entry(String yearMonth, Integer workDays, Double totalWorkHours,
                                                        Double overtimeHours, Double vacationDays, Double sickDays) {
        return UserStatisticHistoryEntryDto.builder()
                .yearMonth(YearMonth.parse(yearMonth))
                .workDays(workDays)
                .totalWorkHours(totalWorkHours)
                .overtimeHours(overtimeHours)
                .vacationDays(vacationDays)
                .sickDays(sickDays)
                .build();
    }

    /**
     * The seven entries TimeTracking really sends (target month plus the six before it), deliberately out of
     * order, with one missing figure in 2023-11. The default window of 6 drops {@link #OLDEST_TRIMMED_MONTH}.
     */
    protected static List<UserStatisticHistoryEntryDto> sevenMonthHistory() {
        return List.of(
                entry("2023-09", 20, 160.0, 0.0, 0.0, 2.0),
                entry(TARGET_MONTH, 22, 175.0, 4.0, 1.0, 0.5),
                entry("2023-12", 21, 168.0, 3.0, 2.0, 1.5),
                entry(OLDEST_TRIMMED_MONTH, 19, 150.5, 0.0, 5.0, 0.0),
                entry("2023-11", 22, 176.0, 1.0, 0.0, null),
                entry("2023-10", 21, 170.0, 2.5, 0.0, 1.0),
                entry(OLDEST_KEPT_MONTH, 18, 140.0, 0.0, 4.0, 0.0));
    }

    /**
     * What the service really hands to the email side: {@link #sevenMonthHistory()} after PromptComposer has
     * sorted it newest-first and trimmed it to 6. Anything consuming a window expects this shape, so tests
     * must not pass the raw shuffled entries in its place.
     */
    protected static List<UserStatisticHistoryEntryDto> sixMonthWindow() {
        return sevenMonthHistory().stream()
                .sorted(java.util.Comparator.comparing(UserStatisticHistoryEntryDto::getYearMonth).reversed())
                .limit(6)
                .toList();
    }

    protected static UserStatisticAnalysisRequestEvent.Builder eventBuilder() {
        return UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID)
                .userId(USER_ID)
                .reportUserName(REPORT_FIRST_NAME)
                .reportUserLastName(REPORT_LAST_NAME)
                .reportEmail(REPORT_EMAIL);
    }

    protected static UserStatisticAnalysisRequestEvent fullEvent() {
        return eventBuilder().entries(sevenMonthHistory()).build();
    }

    /**
     * The single message handed to JavaMailSender, as HTML plus its headers.
     */
    protected static String bodyOf(MimeMessage message) {
        try {
            return message.getContent().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the message body", e);
        }
    }

    /**
     * A mocked JavaMailSender returns null from createMimeMessage(), which blows up MimeMessageHelper, so it
     * hands out a fresh real MimeMessage per call - the message under assertion is then the real thing.
     */
    @BeforeEach
    void stubMimeMessageCreation() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
    }
}
