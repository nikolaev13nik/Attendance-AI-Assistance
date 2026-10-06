package att.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.util.List;

import att.ai.BaseAiAssistanceTest;
import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import jakarta.mail.internet.MimeMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("StatisticAnalysisService: runs every step of the pipeline over one context")
class StatisticAnalysisServiceTest extends BaseAiAssistanceTest {
    @Autowired
    private StatisticAnalysisService statisticAnalysisService;

    @Test
    @DisplayName("one call fills the context in step order and ends in a single email")
    void execute_fillsTheContextAndNotifiesTest() {
        AnalysisContext context = contextOf(fullEvent());

        statisticAnalysisService.execute(context);

        List<YearMonth> months = context.getHistory().stream()
            .map(UserStatisticHistoryEntryDto::getYearMonth)
            .toList();
        assertEquals(6, months.size(),
            "Reason: step one trims the 7 entries sent to the configured window of 6");
        assertEquals(YearMonth.parse(TARGET_MONTH), months.get(0),
            "Reason: the window must be newest-first, which everything downstream reads the ends of");

        assertTrue(context.getPrompt().contains("You are an HR analytics assistant"),
            "Reason: step two renders the prompt from the window the previous step placed");
        assertTrue(context.getPrompt().contains(TARGET_MONTH),
            "Reason: the prompt is built from this context's window, not from a second derivation");

        assertTrue(context.getAnalysis().contains("Placeholder analysis"),
            "Reason: step three parks the model's answer on the context. It is the stub's answer "
                + "because att.ai.llm.provider defaults to 'stub', which is what keeps the suite free "
                + "- see LlmAnalysisClientSelectionTest");

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("the service drives the email step itself, so a context with no recipient sends nothing")
    void execute_withoutRecipientCompletesWithoutSendingTest() {
        AnalysisContext context = contextOf(eventBuilder().reportEmail(null).entries(sevenMonthHistory()).build());

        statisticAnalysisService.execute(context);

        assertFalse(context.getAnalysis().isBlank(),
            "Reason: the analysis still runs - only the delivery is skipped");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
