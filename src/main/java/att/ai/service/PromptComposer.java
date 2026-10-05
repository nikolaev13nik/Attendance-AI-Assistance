package att.ai.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import att.ai.config.AssistanceConfiguration;
import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
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

    private final AssistanceConfiguration configuration;

    public void applyHistoryWindow(AnalysisContext context) {
        context.setHistory(window(context.getEvent().getEntries()));
    }

    public void composePrompt(AnalysisContext context) {
        context.setPrompt(render(context.getHistory()));
    }

    private List<UserStatisticHistoryEntryDto> window(List<UserStatisticHistoryEntryDto> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
            .filter(entry -> Objects.nonNull(entry.getYearMonth()))
            .sorted(Comparator.comparing(UserStatisticHistoryEntryDto::getYearMonth).reversed())
            .limit(configuration.getHistoryMonths())
            .toList();
    }

    private String render(List<UserStatisticHistoryEntryDto> window) {
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

    private String number(Number value) {
        return value == null ? NOT_RECORDED : value.toString();
    }
}
