package att.ai.notification;

/**
 * Wraps a failure to build or hand off a mail message. Unchecked and deliberately NOT listed among the
 * binder's non-retryable exceptions, so an SMTP server that is merely down gets the message redelivered
 * rather than dropped into the DLQ.
 */
public class EmailDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailDeliveryException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
