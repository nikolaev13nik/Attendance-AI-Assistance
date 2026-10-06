package att.ai.llm;

public class LlmRequestRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LlmRequestRejectedException(String msg) {
        super(msg);
    }

    public LlmRequestRejectedException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
