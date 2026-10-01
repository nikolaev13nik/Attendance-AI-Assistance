package att.ai.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import att.ai.service.StatisticAnalysis;

/**
 * Renders the subject and HTML body of the analysis email. Plain string building on purpose: one email with
 * one layout does not justify pulling in a template engine, and this keeps the output visible in one place.
 * Swap in Thymeleaf if the service ever grows a second message type.
 */
@Component
public class EmailContentBuilder {

    private static final String NOT_RECORDED = "&ndash;";

    public String subject(UserStatisticAnalysisRequestEvent event, StatisticAnalysis analysis) {
        List<UserStatisticHistoryEntryDto> history = analysis.history();
        if (history.isEmpty()) {
            return "Work-time analysis for user %d".formatted(event.getUserId());
        }
        // history is newest first, so the last entry is the start of the period
        return "Work-time analysis for user %d (%s - %s)".formatted(event.getUserId(),
                history.get(history.size() - 1).getYearMonth(), history.get(0).getYearMonth());
    }

    public String htmlBody(UserStatisticAnalysisRequestEvent event, StatisticAnalysis analysis) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <body style="font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;font-size:14px;\
                line-height:1.5;color:#1a1a1a;">
                  <p>%s</p>
                  <p>Here is the work-time analysis you requested for user <strong>%d</strong>.</p>
                  %s
                  %s
                  <p style="color:#6b6b6b;font-size:12px;">Generated automatically by Attendance AI Assistance.
                  Please do not reply to this message.</p>
                </body>
                </html>"""
                .formatted(greeting(event), event.getUserId(), paragraphs(analysis.analysis()),
                        historyTable(analysis.history()));
    }

    /** Names are optional in the event contract, so the greeting degrades instead of printing "null". */
    private String greeting(UserStatisticAnalysisRequestEvent event) {
        String name = "%s %s".formatted(
                event.getReportUserName() == null ? "" : event.getReportUserName(),
                event.getReportUserLastName() == null ? "" : event.getReportUserLastName()).trim();
        return name.isEmpty() ? "Hello," : "Hello %s,".formatted(HtmlUtils.htmlEscape(name));
    }

    /**
     * The analysis is model output, so it is escaped before being placed in HTML - a model that emits a
     * stray angle bracket must not be able to inject markup into the email.
     */
    private String paragraphs(String analysis) {
        return analysis.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> "<p>%s</p>".formatted(HtmlUtils.htmlEscape(line)))
                .reduce("", String::concat);
    }

    private String historyTable(List<UserStatisticHistoryEntryDto> history) {
        if (history.isEmpty()) {
            return "<p><em>No monthly statistics were recorded for this period.</em></p>";
        }
        StringBuilder table = new StringBuilder("""
                <table style="border-collapse:collapse;font-size:13px;" cellpadding="6">
                  <thead><tr style="background:#f4f4f5;text-align:left;">
                    <th>Month</th><th>Work days</th><th>Total hours</th>
                    <th>Overtime hours</th><th>Vacation days</th><th>Sick days</th>
                  </tr></thead>
                  <tbody>""");
        history.forEach(entry -> table.append("""
                <tr style="border-top:1px solid #e4e4e7;">\
                <td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"""
                .formatted(entry.getYearMonth(), number(entry.getWorkDays()), number(entry.getTotalWorkHours()),
                        number(entry.getOvertimeHours()), number(entry.getVacationDays()),
                        number(entry.getSickDays()))));
        return table.append("</tbody></table>").toString();
    }

    private String number(Number value) {
        return value == null ? NOT_RECORDED : value.toString();
    }
}
