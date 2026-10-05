package att.ai.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;

@Component
public class EmailContentHandler {

    private static final String NOT_RECORDED = "&ndash;";

    public String subject(AnalysisContext context) {
        List<UserStatisticHistoryEntryDto> history = context.getHistory();
        Integer userId = context.getEvent().getUserId();
        if (history.isEmpty()) {
            return "Work-time analysis for user %d".formatted(userId);
        }
        return "Work-time analysis for user %d (%s - %s)".formatted(userId,
                history.get(history.size() - 1).getYearMonth(), history.get(0).getYearMonth());
    }

    public String htmlBody(AnalysisContext context) {
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
            .formatted(greeting(context.getEvent()), context.getEvent().getUserId(),
                paragraphs(context.getAnalysis()), historyTable(context.getHistory()));
    }

    private String greeting(UserStatisticAnalysisRequestEvent event) {
        String name = "%s %s".formatted(
                event.getReportUserName() == null ? "" : event.getReportUserName(),
                event.getReportUserLastName() == null ? "" : event.getReportUserLastName()).trim();
        return name.isEmpty() ? "Hello," : "Hello %s,".formatted(HtmlUtils.htmlEscape(name));
    }

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
