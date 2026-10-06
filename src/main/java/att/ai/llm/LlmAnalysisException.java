package att.ai.llm;

public class LlmAnalysisException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LlmAnalysisException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
