package vn.edu.music.common.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** Strict JSON/envelope codec. Call ProtocolValidator for message-specific validation. */
public final class JsonMessageCodec {
    private final ObjectMapper mapper = ContractJson.mapper();
    private static final Set<String> FIELDS = Set.of("type", "protocolVersion", "requestId", "roomId", "payload");

    public byte[] encode(ProtocolMessage message) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(Objects.requireNonNull(message, "message is required"));
        checkLength(bytes);
        return bytes;
    }

    public ProtocolMessage decode(byte[] json) throws IOException {
        checkLength(json);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException error) {
            ProtocolException malformed = new ProtocolException("Malformed control JSON");
            malformed.initCause(error);
            throw malformed;
        }
        ProtocolException.Correlation context = trustedEnvelope(root);
        MessageType type;
        try {
            type = MessageType.valueOf(root.get("type").textValue());
        } catch (IllegalArgumentException error) {
            ErrorCode code = root.get("protocolVersion").intValue() == ProtocolLimits.VERSION
                    ? ErrorCode.UNSUPPORTED_MESSAGE : ErrorCode.UNSUPPORTED_VERSION;
            throw new ProtocolException(code, "Unsupported message type or protocol version", context);
        }
        try {
            return mapper.treeToValue(root, ProtocolMessage.class);
        } catch (IOException error) {
            ProtocolException invalid = new ProtocolException(ErrorCode.INVALID_REQUEST, "Invalid control envelope", context);
            invalid.initCause(error);
            throw invalid;
        }
    }

    private ProtocolException.Correlation trustedEnvelope(JsonNode root) throws ProtocolException {
        if (root == null || !root.isObject()) throw new ProtocolException("Expected an envelope object");
        var fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!FIELDS.contains(fields.next())) throw new ProtocolException("Unknown envelope field");
        }
        if (!root.hasNonNull("type") || !root.get("type").isTextual() || root.get("type").textValue().isBlank()
                || !root.hasNonNull("protocolVersion") || !root.get("protocolVersion").isIntegralNumber()
                || !root.get("protocolVersion").canConvertToInt()
                || !root.hasNonNull("requestId") || !root.get("requestId").isTextual()
                || !root.hasNonNull("payload") || !root.get("payload").isObject()
                || (root.hasNonNull("roomId") && !root.get("roomId").isTextual())) {
            throw new ProtocolException("Untrustworthy envelope fields");
        }
        try {
            return new ProtocolException.Correlation(root.get("requestId").textValue(),
                    root.hasNonNull("roomId") ? root.get("roomId").textValue() : null);
        } catch (IllegalArgumentException error) {
            throw new ProtocolException("Untrustworthy envelope identity");
        }
    }

    private void checkLength(byte[] bytes) throws ProtocolException {
        if (bytes.length > ProtocolLimits.MAX_FRAME_BYTES - 1) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE, "Control JSON exceeds v1 limit");
        }
    }
}
