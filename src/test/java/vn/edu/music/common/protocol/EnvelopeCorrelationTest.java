package vn.edu.music.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.common.protocol.ContractFixtures.*;

class EnvelopeCorrelationTest {
    private final JsonMessageCodec json = new JsonMessageCodec();

    private String raw(String type) {
        return "{\"type\":\"" + type + "\",\"protocolVersion\":1,\"requestId\":\"" + ID
                + "\",\"roomId\":\"r1\",\"payload\":{}}";
    }

    @Test
    void unknownTypePreservesTrustedRequestIdAndRoomForError() throws Exception {
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> json.decode(raw("FUTURE_MESSAGE").getBytes(StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.UNSUPPORTED_MESSAGE, error.code());
        ProtocolException.Correlation context = error.correlation().orElseThrow();
        assertEquals(ID, context.requestId()); assertEquals("r1", context.roomId());
        ProtocolMessage reply = PayloadCodec.message(MessageType.ERROR, context.requestId(), context.roomId(),
                new Payloads.ErrorPayload(error.code(), error.getMessage(), false, null));
        assertEquals(ID, json.decode(json.encode(reply)).requestId());
    }

    @Test
    void unknownTypeBeforeJoinCanCorrelateWithOmittedRoomId() {
        String input = raw("FUTURE_MESSAGE").replace("\"roomId\":\"r1\",", "");
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> json.decode(input.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ID, error.correlation().orElseThrow().requestId());
        assertNull(error.correlation().orElseThrow().roomId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_STATE", "ROOM_CLOSED", "AUDIO_CHUNK", "ERROR"})
    void serverTypeFromClientIsUnsupportedDirectionEvenIfPayloadIsNotAResponseSchema(String type) throws Exception {
        ProtocolMessage envelope = json.decode(raw(type).getBytes(StandardCharsets.UTF_8));
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> ProtocolValidator.validateClientRequest(envelope));
        assertEquals(ErrorCode.UNSUPPORTED_MESSAGE, error.code());
        assertEquals(ID, error.correlation().orElseThrow().requestId());
    }

    @Test
    void clientRequestFromServerIsUnsupportedDirection() throws Exception {
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> ProtocolValidator.validateServerMessage(message(MessageType.PING)));
        assertEquals(ErrorCode.UNSUPPORTED_MESSAGE, error.code());
        assertEquals(ID, error.correlation().orElseThrow().requestId());
    }

    @Test
    void knownPayloadErrorKeepsCorrelationWithoutLeakingParserDetails() throws Exception {
        ProtocolMessage badPayload = json.decode(raw("PLAY").getBytes(StandardCharsets.UTF_8));
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> ProtocolValidator.validateClientRequest(badPayload));
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertEquals(ID, error.correlation().orElseThrow().requestId());
        assertEquals("Invalid PLAY payload", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{}",
            "{\"type\":\"PING\",\"type\":\"PING\"}",
            "{\"type\":\"PING\",\"protocolVersion\":1}"})
    void malformedJsonOrEnvelopeCannotBeCorrelated(String raw) {
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> json.decode(raw.getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.correlation().isEmpty());
        assertNotEquals(ErrorCode.INTERNAL_ERROR, error.code());
    }

    @Test
    void unsafeFieldVariationsDoNotEchoAnUntrustedId() {
        String valid = raw("FUTURE_MESSAGE");
        for (String input : new String[]{
                valid.replace(ID, "invalid-id"),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\""),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":1.5"),
                valid.replace("\"roomId\":\"r1\"", "\"roomId\":[]"),
                valid.replace("\"roomId\":\"r1\"", "\"roomId\":\"../unsafe\""),
                valid.replace("\"payload\":{}", "\"payload\":null"),
                valid.replace("\"payload\":{}", "\"payload\":[]"),
                valid.replace("\"payload\":{}", "\"payload\":{\"x\":1,\"x\":2}"),
                valid.replace("\"payload\":{}", "\"payload\":{},\"extra\":true"),
                valid + "{}"}) {
            ProtocolException error = assertThrows(ProtocolException.class,
                    () -> json.decode(input.getBytes(StandardCharsets.UTF_8)), input);
            assertTrue(error.correlation().isEmpty(), input);
            assertNotEquals(ErrorCode.INTERNAL_ERROR, error.code());
        }
    }

    @Test
    void knownUnsupportedVersionCanBeCorrelatedByValidator() throws Exception {
        ProtocolMessage future = new ProtocolMessage(MessageType.PING, 2, ID, null, Map.of("clientSendTimeMs", TIME));
        ProtocolException error = assertThrows(ProtocolException.class, () -> ProtocolValidator.validateClientRequest(future));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, error.code());
        assertEquals(ID, error.correlation().orElseThrow().requestId());
    }

    @Test
    void validDirectionStillUsesTheFullPayloadValidator() throws Exception {
        assertInstanceOf(Payloads.Ping.class, ProtocolValidator.validateClientRequest(message(MessageType.PING)));
        assertInstanceOf(Payloads.Pong.class, ProtocolValidator.validateServerMessage(message(MessageType.PONG)));
    }
}
