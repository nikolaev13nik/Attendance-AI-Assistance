package att.ai.notification;

public class EmailDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailDeliveryException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
