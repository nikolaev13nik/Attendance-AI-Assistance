package att.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.util.List;

import att.ai.BaseAiAssistanceTest;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PromptComposer: trims the history to the configured window and renders it for the model")
class PromptComposerTest extends BaseAiAssistanceTest {

    @Autowired
    private PromptComposer promptComposer;

    @Test
    @DisplayName("the window is the most recent months, newest first, however the entries arrived ordered")
    void historyWindow_sortsNewestFirstAndTrimsToConfiguredMonthsTest() {
        List<YearMonth> months = promptComposer.historyWindow(sevenMonthHistory()).stream()
                .map(UserStatisticHistoryEntryDto::getYearMonth)
                .toList();

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
    void historyWindow_dropsEntriesWithoutYearMonthTest() {
        UserStatisticHistoryEntryDto noMonth = UserStatisticHistoryEntryDto.builder().workDays(10).build();

        List<UserStatisticHistoryEntryDto> window = promptComposer.historyWindow(
                List.of(noMonth, entry(TARGET_MONTH, 22, 175.0, 4.0, 1.0, 0.5)));

        assertEquals(1, window.size(), "Reason: an entry with no month cannot be placed in a trend");
        assertEquals(YearMonth.parse(TARGET_MONTH), window.get(0).getYearMonth());
    }

    @Test
    @DisplayName("a null entries list is tolerated - the event contract does not guarantee one")
    void historyWindow_nullEntriesYieldsEmptyWindowTest() {
        assertTrue(promptComposer.historyWindow(null).isEmpty(),
                "Reason: entries are optional in the schema, so null must not blow up the consumer");
    }

    @Test
    @DisplayName("the prompt carries the instructions and one aligned row per month")
    void compose_rendersInstructionsAndOneRowPerMonthTest() {
        String prompt = promptComposer.compose(promptComposer.historyWindow(sevenMonthHistory()));

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
    void compose_rendersMissingFigureAsDashTest() {
        String prompt = promptComposer.compose(promptComposer.historyWindow(
                List.of(entry(TARGET_MONTH, 22, 175.0, null, 1.0, 0.5))));

        String row = prompt.lines().filter(line -> line.startsWith(TARGET_MONTH)).findFirst().orElseThrow();
        assertTrue(row.contains("-"),
                "Reason: an unrecorded figure must not read as 0.0, which the model would treat as 'no overtime'");
        assertFalse(row.contains("0.0 "), "Reason: the missing overtime must not have become a zero");
    }

    @Test
    @DisplayName("an empty history says so instead of sending a bare table header")
    void compose_emptyHistoryStatesThereIsNoDataTest() {
        String prompt = promptComposer.compose(List.of());

        assertTrue(prompt.contains("No monthly statistics were recorded"),
                "Reason: the model must be told the history is empty rather than shown an empty table");
        assertFalse(prompt.contains("Work days"), "Reason: no table header without rows to put under it");
    }
}
