package att.ai.context;

import java.util.List;

import att.ai.messaging.dto.UserStatisticAnalysisRequestEvent;
import att.ai.messaging.dto.UserStatisticHistoryEntryDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisContext {

    private UserStatisticAnalysisRequestEvent event;

    @Builder.Default
    private List<UserStatisticHistoryEntryDto> history = List.of();
    private String prompt;
    private String analysis;
}
