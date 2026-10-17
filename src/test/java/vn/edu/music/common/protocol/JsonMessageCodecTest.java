package vn.edu.music.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonMessageCodecTest {
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private static final String JSON = """
            {"type":"CREATE_ROOM","protocolVersion":1,
             "requestId":"12345678-1234-1234-1234-123456789abc","roomId":null,
             "payload":{"nickname":"Minh"}}
            """;

    @Test
    void roundTripPreservesEnvelopeAndNestedPayload() throws Exception {
        ProtocolMessage request = ProtocolMessage.request(MessageType.CREATE_ROOM, null,
                Map.of("nickname", "Minh", "nested", Map.of("ids", List.of("a", "b"))));
        assertEquals(request, codec.decode(codec.encode(request)));
    }

    @Test
    void acceptsOptionalRoomIdOmittedBeforeJoin() throws Exception {
        ProtocolMessage request = codec.decode(JSON.replace("\"roomId\":null,", "")
                .getBytes(StandardCharsets.UTF_8));
        assertNull(request.roomId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "{\"type\":\"UNKNOWN\"}",
            "{\"type\":\"PING\",\"type\":\"PING\"}"})
    void rejectsInvalidEnvelopes(String json) {
        assertThrows(IOException.class, () -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnknownFieldsTrailingDataAndInvalidId() {
        assertInvalid(JSON.replace("\"roomId\":null", "\"roomId\":null,\"extra\":true"));
        assertInvalid(JSON + "{}");
        assertInvalid(JSON.replace("12345678-1234-1234-1234-123456789abc", "not-a-uuid"));
    }

    @Test
    void payloadMapIsCopiedAndReadOnly() {
        Map<String, Object> source = new LinkedHashMap<>(Map.of("nickname", "Minh"));
        ProtocolMessage request = ProtocolMessage.request(MessageType.CREATE_ROOM, null, source);
        source.put("nickname", "changed");
        assertEquals("Minh", request.payload().get("nickname"));
        assertThrows(UnsupportedOperationException.class, () -> request.payload().put("x", 1));
    }

    private void assertInvalid(String json) {
        assertThrows(IOException.class, () -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }
}
