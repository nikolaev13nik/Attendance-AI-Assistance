package att.ai.service;

import org.springframework.stereotype.Service;

import att.ai.context.AnalysisContext;
import att.ai.llm.LlmAnalysisClient;
import att.ai.notification.EmailNotificationService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StatisticAnalysisService {

    private final PromptComposer promptComposer;
    private final LlmAnalysisClient llmAnalysisClient;
    private final EmailNotificationService emailNotificationService;

    public void execute(AnalysisContext context) {
        promptComposer.applyHistoryWindow(context);
        promptComposer.composePrompt(context);
        requestAnalysis(context);
        emailNotificationService.execute(context);
    }

    private void requestAnalysis(AnalysisContext context) {
        context.setAnalysis(llmAnalysisClient.analyse(context.getPrompt()));
    }
}
