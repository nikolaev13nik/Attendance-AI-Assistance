package att.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.util.List;

import att.ai.BaseAiAssistanceTest;
import att.ai.context.AnalysisContext;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PromptComposer: trims the history to the configured window and renders it for the model")
class PromptComposerTest extends BaseAiAssistanceTest {

    @Autowired
    private PromptComposer promptComposer;

    /**
     * Both steps in order, as StatisticAnalysisService calls them, so the prompt is built from the window.
     */
    private AnalysisContext windowedAndComposed(List<UserStatisticHistoryEntryDto> entries) {
        AnalysisContext context = contextOf(eventBuilder().entries(entries).build());
        promptComposer.applyHistoryWindow(context);
        promptComposer.composePrompt(context);
        return context;
    }

    private List<YearMonth> monthsOfWindow(List<UserStatisticHistoryEntryDto> entries) {
        AnalysisContext context = contextOf(eventBuilder().entries(entries).build());
        promptComposer.applyHistoryWindow(context);
        return context.getHistory().stream().map(UserStatisticHistoryEntryDto::getYearMonth).toList();
    }

    @Test
    @DisplayName("the window is the most recent months, newest first, however the entries arrived ordered")
    void applyHistoryWindow_sortsNewestFirstAndTrimsToConfiguredMonthsTest() {
        List<YearMonth> months = monthsOfWindow(sevenMonthHistory());

        assertEquals(6, months.size(),
                "Reason: att.ai.analysis.history-months=6, so only 6 of the 7 entries sent are used");
        assertEquals(YearMonth.parse(TARGET_MONTH), months.get(0),
                "Reason: the prompt must start at the newest month even though the input was shuffled");
        assertEquals(YearMonth.parse(OLDEST_KEPT_MONTH), months.get(months.size() - 1),
                "Reason: the window keeps the 6 newest months, so 2023-08 is the oldest kept");
        assertFalse(months.contains(YearMonth.parse(OLDEST_TRIMMED_MONTH)),
                "Reason: the 7th-oldest month falls outside the window and must be dropped");
        assertEquals(months.stream().sorted(java.util.Comparator.reverseOrder()).toList(), months,
                "Reason: the order must be strictly newest-first so the model reads a consistent sequence");
    }

    @Test
    @DisplayName("entries without a month are unusable and are dropped rather than rendered blank")
    void applyHistoryWindow_dropsEntriesWithoutYearMonthTest() {
        UserStatisticHistoryEntryDto noMonth = UserStatisticHistoryEntryDto.builder().workDays(10).build();

        List<YearMonth> months = monthsOfWindow(List.of(noMonth, entry(TARGET_MONTH, 22, 175.0, 4.0, 1.0, 0.5)));

        assertEquals(1, months.size(), "Reason: an entry with no month cannot be placed in a trend");
        assertEquals(YearMonth.parse(TARGET_MONTH), months.get(0));
    }

    @Test
    @DisplayName("a null entries list is tolerated - the event contract does not guarantee one")
    void applyHistoryWindow_nullEntriesYieldsEmptyWindowTest() {
        assertTrue(monthsOfWindow(null).isEmpty(),
                "Reason: entries are optional in the schema, so null must not blow up the consumer");
    }

    @Test
    @DisplayName("the prompt carries the instructions and one aligned row per month")
    void composePrompt_rendersInstructionsAndOneRowPerMonthTest() {
        String prompt = windowedAndComposed(sevenMonthHistory()).getPrompt();

        assertTrue(prompt.contains("You are an HR analytics assistant"),
                "Reason: the model needs the instructions, not just numbers");
        assertTrue(prompt.contains("Month    Work days  Total hours  Overtime hours  Vacation days  Sick days"),
                "Reason: the table header tells the model what each column means");
        assertTrue(prompt.contains(TARGET_MONTH) && prompt.contains(OLDEST_KEPT_MONTH),
                "Reason: every month of the window must appear");
        assertFalse(prompt.contains(OLDEST_TRIMMED_MONTH),
                "Reason: a trimmed month must not leak into the prompt");
        assertEquals(6, prompt.lines().filter(line -> line.startsWith("202")).count(),
                "Reason: exactly one data row per month in the window");
    }

    @Test
    @DisplayName("a missing figure is rendered as missing, not as zero")
    void composePrompt_rendersMissingFigureAsDashTest() {
        String prompt = windowedAndComposed(List.of(entry(TARGET_MONTH, 22, 175.0, null, 1.0, 0.5))).getPrompt();

        String row = prompt.lines().filter(line -> line.startsWith(TARGET_MONTH)).findFirst().orElseThrow();
        assertTrue(row.contains("-"),
                "Reason: an unrecorded figure must not read as 0.0, which the model would treat as 'no overtime'");
        assertFalse(row.contains("0.0 "), "Reason: the missing overtime must not have become a zero");
    }

    @Test
    @DisplayName("an empty history says so instead of sending a bare table header")
    void composePrompt_emptyHistoryStatesThereIsNoDataTest() {
        String prompt = windowedAndComposed(List.of()).getPrompt();

        assertTrue(prompt.contains("No monthly statistics were recorded"),
                "Reason: the model must be told the history is empty rather than shown an empty table");
        assertFalse(prompt.contains("Work days"), "Reason: no table header without rows to put under it");
    }

    @Test
    @DisplayName("composing before the window step still renders, because the context starts with an empty one")
    void composePrompt_withoutWindowStepUsesTheContextDefaultTest() {
        // pins the @Builder.Default on AnalysisContext.history: out-of-order steps must degrade to "no data"
        // rather than NPE, which is what replaced the old "compose() needs a pre-sorted list" hazard
        AnalysisContext context = contextOf(fullEvent());
        promptComposer.composePrompt(context);

        assertTrue(context.getPrompt().contains("No monthly statistics were recorded"),
                "Reason: history defaults to an empty list, so the prompt states there is nothing to analyse");
    }
}
