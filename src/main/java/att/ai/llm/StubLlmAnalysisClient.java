package att.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "att.ai.llm.provider", havingValue = "stub", matchIfMissing = true)
public class StubLlmAnalysisClient implements LlmAnalysisClient {
    private static final Logger log = LoggerFactory.getLogger(StubLlmAnalysisClient.class);

    private static final String STUB_ANALYSIS = """
            Overall the recorded working time is stable across the reported period, with no month \
            deviating far from the employee's own average.

            Overtime appears in short bursts rather than continuously, which usually points at deadline \
            peaks rather than a structural workload problem. Absence is spread thinly over the period \
            instead of clustering, so no single month stands out as disrupted.

            Nothing here needs immediate attention. Worth revisiting next month to see whether the \
            overtime pattern repeats.

            (Placeholder analysis - no language model has been connected to this service yet.)""";

    @Override
    public String analyse(String prompt) {
        log.info("LLM prompt that would be sent:\n{}", prompt);
        return STUB_ANALYSIS;
    }
}
