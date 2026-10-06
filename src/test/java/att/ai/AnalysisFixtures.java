package att.ai;

import java.time.YearMonth;
import java.util.List;

import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import jakarta.mail.internet.MimeMessage;

public abstract class AnalysisFixtures {
    protected static final Integer TENANT_ID = 2;
    protected static final Integer USER_ID = 7;
    protected static final String REPORT_FIRST_NAME = "John";
    protected static final String REPORT_LAST_NAME = "Doe";
    protected static final String REPORT_EMAIL = "john.doe@example.com";
    protected static final String FROM_ADDRESS = "no-reply@attendance.test";

    protected static final String TARGET_MONTH = "2024-01";
    protected static final String OLDEST_KEPT_MONTH = "2023-08";
    protected static final String OLDEST_TRIMMED_MONTH = "2023-07";

    protected static final String ANALYSIS_TEXT = "Workload is steady.\n\nOvertime is occasional.";

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

    protected static AnalysisContext contextOf(UserStatisticAnalysisRequestEvent event) {
        return AnalysisContext.builder().event(event).build();
    }

    protected static AnalysisContext analysedContext() {
        return analysedContext(fullEvent(), sixMonthWindow(), ANALYSIS_TEXT);
    }

    protected static AnalysisContext analysedContext(UserStatisticAnalysisRequestEvent event,
                                                     List<UserStatisticHistoryEntryDto> history,
                                                     String analysis) {
        return AnalysisContext.builder().event(event).history(history).analysis(analysis).build();
    }

    protected static String bodyOf(MimeMessage message) {
        try {
            return message.getContent().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the message body", e);
        }
    }
}
