package att.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;

/**
 * Turns the statistic history carried by a Kafka message into the text handed to the LLM.
 */
@Component
public class PromptComposer {

    private static final String INSTRUCTIONS = """
            You are an HR analytics assistant. Below is the recorded monthly work-time history of a single
            employee, most recent month first. Describe the trend in plain language for a manager: workload
            direction, whether overtime is occasional or sustained, and whether absence is clustered or
            spread out. Mention only what the numbers support, and say so when the history is too short to
            draw a conclusion. Do not invent months that are not listed.

            """;

    private static final String TABLE_HEADER = """
            Month    Work days  Total hours  Overtime hours  Vacation days  Sick days
            -------  ---------  -----------  --------------  -------------  ---------
            """;

    private static final String ROW_FORMAT = "%-7s  %9s  %11s  %14s  %13s  %9s%n";
    private static final String NOT_RECORDED = "-";

    /** Months of history to include. The producer sends the target month plus 6 earlier ones. */
    @Value("${att.ai.analysis.history-months:6}")
    private int historyMonths;

    public String compose(UserStatisticAnalysisRequestEvent event) {
        List<UserStatisticHistoryEntryDto> window = historyWindow(event.getEntries());

        StringBuilder prompt = new StringBuilder(INSTRUCTIONS);
        if (window.isEmpty()) {
            return prompt.append("No monthly statistics were recorded for this employee.").toString();
        }

        prompt.append(TABLE_HEADER);
        window.forEach(entry -> prompt.append(String.format(ROW_FORMAT,
                entry.getYearMonth(),
                number(entry.getWorkDays()),
                number(entry.getTotalWorkHours()),
                number(entry.getOvertimeHours()),
                number(entry.getVacationDays()),
                number(entry.getSickDays()))));
        return prompt.toString();
    }

    /**
     * The most recent {@code historyMonths} entries, newest first. The incoming list is already ordered and
     * capped by the producer, but it is re-sorted here so the prompt stays deterministic regardless of how
     * the message was produced, and entries with no month are dropped as unusable.
     */
    List<UserStatisticHistoryEntryDto> historyWindow(List<UserStatisticHistoryEntryDto> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> Objects.nonNull(entry.getYearMonth()))
                .sorted(Comparator.comparing(UserStatisticHistoryEntryDto::getYearMonth).reversed())
                .limit(historyMonths)
                .toList();
    }

    /** Statistic figures are optional in the contract, so a missing one is shown as missing, not as zero. */
    private String number(Number value) {
        return value == null ? NOT_RECORDED : value.toString();
    }
}
