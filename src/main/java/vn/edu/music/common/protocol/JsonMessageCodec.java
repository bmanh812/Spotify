package vn.edu.music.common.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/** JSON and envelope parsing only. Message-specific payload validation belongs to Phase 2. */
public final class JsonMessageCodec {
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public byte[] encode(ProtocolMessage message) throws IOException {
        return mapper.writeValueAsBytes(Objects.requireNonNull(message, "message is required"));
    }

    public ProtocolMessage decode(byte[] json) throws IOException {
        ProtocolMessage message = mapper.readValue(json, ProtocolMessage.class);
        if (message == null) {
            throw new ProtocolException("JSON envelope cannot be null");
        }
        return message;
    }
}
