package att.ai.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import att.ai.BaseAiAssistanceTest;
import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.service.StatisticAnalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EmailContentBuilder: renders the subject and HTML body of the analysis email")
class EmailContentBuilderTest extends BaseAiAssistanceTest {

    private static final String ANALYSIS_TEXT = "Workload is steady.\n\nOvertime is occasional.";

    @Autowired
    private EmailContentBuilder contentBuilder;

    private StatisticAnalysis analysisOverWindow() {
        return new StatisticAnalysis(sixMonthWindow(), ANALYSIS_TEXT);
    }

    @Test
    @DisplayName("the subject names the analysed user and the period the history covers")
    void subject_namesUserAndPeriodTest() {
        // subject() reads the ends of the window, which is newest-first - so this also pins that it is
        // handed an already-ordered list rather than the raw entries off the event
        assertEquals("Work-time analysis for user 7 (2023-08 - 2024-01)",
                contentBuilder.subject(fullEvent(), analysisOverWindow()));
    }

    @Test
    @DisplayName("with no history the subject drops the period instead of printing an empty range")
    void subject_withoutHistoryOmitsPeriodTest() {
        assertEquals("Work-time analysis for user 7",
                contentBuilder.subject(fullEvent(), new StatisticAnalysis(List.of(), ANALYSIS_TEXT)));
    }

    @Test
    @DisplayName("the body greets the requester by name and tables every month of the window")
    void htmlBody_greetsRequesterAndTablesHistoryTest() {
        String body = contentBuilder.htmlBody(fullEvent(), analysisOverWindow());

        assertTrue(body.contains("Hello John Doe,"),
                "Reason: the email goes to the requester, so it should address them");
        assertTrue(body.contains("<p>Workload is steady.</p>") && body.contains("<p>Overtime is occasional.</p>"),
                "Reason: each paragraph of the analysis becomes its own <p>");
        assertEquals(6, body.split("border-top:1px solid #e4e4e7", -1).length - 1,
                "Reason: one data row per month of the window, and none for the styled header row");
        assertTrue(body.contains("<td>2024-01</td>") && body.contains("<td>2023-08</td>"),
                "Reason: the window's newest and oldest months must both be in the table");
        assertFalse(body.contains("<td>2023-07</td>"),
                "Reason: the trimmed month must not appear in the email either");
    }

    @Test
    @DisplayName("missing names degrade to a neutral greeting rather than printing null")
    void htmlBody_withoutRequesterNameUsesNeutralGreetingTest() {
        UserStatisticAnalysisRequestEvent anonymous = UserStatisticAnalysisRequestEvent.builder()
                .tenantId(TENANT_ID).userId(USER_ID).reportEmail(REPORT_EMAIL).build();

        String body = contentBuilder.htmlBody(anonymous, analysisOverWindow());

        assertTrue(body.contains("<p>Hello,</p>"),
                "Reason: first and last name are optional in the event contract");
        assertFalse(body.contains("null"), "Reason: a missing name must never surface as the text 'null'");
    }

    @Test
    @DisplayName("model output is escaped - a stray tag in the analysis cannot inject markup")
    void htmlBody_escapesModelOutputTest() {
        String body = contentBuilder.htmlBody(fullEvent(),
                new StatisticAnalysis(List.of(), "Trend <script>alert(1)</script> unclear."));

        assertTrue(body.contains("&lt;script&gt;"),
                "Reason: the analysis is untrusted text and must be escaped before entering HTML");
        assertFalse(body.contains("<script>"), "Reason: the raw tag must not survive into the body");
    }

    @Test
    @DisplayName("an unrecorded figure shows as a dash and an empty history says so")
    void htmlBody_rendersMissingDataExplicitlyTest() {
        String withGap = contentBuilder.htmlBody(fullEvent(), analysisOverWindow());
        assertTrue(withGap.contains("&ndash;"),
                "Reason: 2023-11 has no sickDays in the fixture, which must not render as 0.0");

        String empty = contentBuilder.htmlBody(fullEvent(), new StatisticAnalysis(List.of(), ANALYSIS_TEXT));
        assertTrue(empty.contains("No monthly statistics were recorded for this period."),
                "Reason: an empty period is stated, not shown as an empty table");
        assertFalse(empty.contains("<table"), "Reason: no table element when there is nothing to tabulate");
    }
}
