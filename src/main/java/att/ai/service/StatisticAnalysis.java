package att.ai.service;

import java.util.List;

import att.ai.messaging.dto.UserStatisticHistoryEntryDto;

/**
 * Result of one analysis run: the history the model was actually shown, and what it answered. Carrying the
 * window alongside the text means the email can table exactly the months the analysis talks about, without
 * trimming and sorting the entries a second time.
 *
 * @param history the months included, newest first
 * @param analysis the model's prose
 */
public record StatisticAnalysis(List<UserStatisticHistoryEntryDto> history, String analysis) {
}
