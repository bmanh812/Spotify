package vn.edu.music.common.protocol;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public final class ProtocolException extends IOException {
    private final ErrorCode code;
    private final Correlation correlation;

    public ProtocolException(String message) {
        this(ErrorCode.INVALID_REQUEST, message);
    }

    public ProtocolException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ProtocolException(ErrorCode code, String message, Correlation correlation) {
        super(message);
        this.code = code;
        this.correlation = correlation;
    }

    public ErrorCode code() {
        return code;
    }

    /** Empty means the future transport must close, not invent a response requestId. */
    public Optional<Correlation> correlation() {
        return Optional.ofNullable(correlation);
    }

    ProtocolException withCorrelation(Correlation context) {
        if (correlation != null || context == null) return this;
        ProtocolException correlated = new ProtocolException(code, getMessage(), context);
        correlated.initCause(this);
        return correlated;
    }

    /** Safely parsed envelope context; contains no message type or business authorization. */
    public record Correlation(String requestId, String roomId) {
        public Correlation {
            if (requestId == null || !UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
                throw new IllegalArgumentException("Invalid correlation requestId");
            }
            if (roomId != null && !roomId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Invalid correlation roomId");
            }
        }
    }
}
