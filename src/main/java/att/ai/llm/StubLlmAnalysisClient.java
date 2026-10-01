package att.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder analysis so the whole pipeline - consume, compose, notify - can be run and tested before any
 * model is wired up. Returns a fixed text and logs the prompt it was given.
 * <p>
 * When a real client arrives, gate the two implementations on a property (for example
 * {@code att.ai.llm.provider}) rather than deleting this one: it keeps the pipeline testable without
 * calling out to a paid API.
 */
@Service
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
        log.debug("LLM prompt that would be sent:\n{}", prompt);
        return STUB_ANALYSIS;
    }
}
