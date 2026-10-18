package vn.edu.music.common.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.common.protocol.ContractFixtures.*;

class ProtocolContractTest {
    private final JsonMessageCodec json = new JsonMessageCodec();

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = "AUDIO_CHUNK", mode = EnumSource.Mode.EXCLUDE)
    void allControlMessagesSerializeAndValidate(MessageType type) throws Exception {
        ProtocolMessage original = message(type);
        ProtocolMessage decoded = json.decode(json.encode(original));
        assertEquals(PayloadCodec.decode(original), PayloadCodec.decode(decoded));
        assertEquals(ID, decoded.requestId());
    }

    @TestFactory
    Stream<DynamicTest> everyRequiredPayloadFieldIsActuallyRequired() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (MessageType type : MessageType.values()) {
            if (type == MessageType.AUDIO_CHUNK) continue;
            ProtocolMessage valid = message(type);
            for (RecordComponent field : PayloadCodec.decode(valid).getClass().getRecordComponents()) {
                JsonProperty annotation = field.getAccessor().getAnnotation(JsonProperty.class);
                if (annotation == null || !annotation.required()) continue;
                tests.add(DynamicTest.dynamicTest(type + " missing " + field.getName(), () -> {
                    Map<String, Object> missing = new LinkedHashMap<>(valid.payload());
                    missing.remove(field.getName());
                    assertThrows(ProtocolException.class, () -> PayloadCodec.decode(
                            new ProtocolMessage(type, 1, ID, valid.roomId(), missing)));
                }));
            }
        }
        assertFalse(tests.isEmpty());
        return tests.stream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "protocolVersion", "requestId", "payload"})
    void envelopeRequiredFieldsCannotDefault(String field) throws Exception {
        ObjectMapper mapper = ContractJson.mapper();
        var node = mapper.valueToTree(message(MessageType.PING));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(field);
        assertThrows(java.io.IOException.class, () -> json.decode(mapper.writeValueAsBytes(node)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "2.5", "null", "true", "-1", "0"})
    void malformedVersionFieldIsRejected(String value) throws Exception {
        String raw = "{\"type\":\"PLAY\",\"protocolVersion\":1,\"requestId\":\"" + ID
                + "\",\"roomId\":\"r1\",\"payload\":{\"expectedRoomVersion\":" + value + "}}";
        assertThrows(java.io.IOException.class, () -> PayloadCodec.decode(json.decode(raw.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsUnknownNullAndWrongTypePayloadFields() throws Exception {
        assertInvalid(MessageType.CREATE_ROOM, null, Map.of("nickname", 123));
        Map<String, Object> nullName = new HashMap<>(); nullName.put("nickname", null);
        assertInvalid(MessageType.CREATE_ROOM, null, nullName);
        assertInvalid(MessageType.CREATE_ROOM, null, Map.of("nickname", "Minh", "extra", true));
        assertInvalid(MessageType.JOIN_ROOM, null, Map.of("nickname", "Minh", "roomCode", "abc123"));
        assertInvalid(MessageType.LEAVE_ROOM, "r1", Map.of("clientId", "forged"));
        assertInvalid(MessageType.SEEK, "r1", Map.of("positionMs", -1, "expectedRoomVersion", 1));
    }

    @Test
    void rejectsRoomScopeMismatchAndBinaryInControlEnvelope() throws Exception {
        assertInvalid(MessageType.PING, "r1", Map.of("clientSendTimeMs", TIME));
        assertInvalid(MessageType.PLAY, null, Map.of("expectedRoomVersion", 1));
        ProtocolMessage update = message(MessageType.PLAYBACK_STATE);
        assertInvalid(MessageType.PLAYBACK_STATE, "r2", update.payload());
        assertInvalid(MessageType.AUDIO_CHUNK, "r1", Map.of());
    }

    @Test
    void unsupportedVersionHasSpecificError() throws Exception {
        ProtocolMessage ping = message(MessageType.PING);
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, assertThrows(ProtocolException.class,
                () -> PayloadCodec.decode(new ProtocolMessage(ping.type(), 2, ID, null, ping.payload()))).code());
    }

    @Test
    void timestampAndMetricValidation() throws Exception {
        assertInvalid(MessageType.PONG, null, Map.of("clientSendTimeMs", TIME,
                "serverReceiveTimeMs", TIME + 10, "serverSendTimeMs", TIME));
        assertInvalid(MessageType.PING, null, Map.of("clientSendTimeMs", 0));
        ProtocolMessage metrics = message(MessageType.CLIENT_METRICS);
        Map<String, Object> invalid = new HashMap<>(metrics.payload()); invalid.put("rttMs", Double.NaN);
        assertInvalid(MessageType.CLIENT_METRICS, "r1", invalid);
        assertEquals(-10, ((Payloads.ClientMetrics) PayloadCodec.decode(metrics)).driftMs());
    }

    @Test
    void errorRetrySafetyIsExplicitAndErrorKeepsRequestId() throws Exception {
        ProtocolMessage error = ProtocolMessage.error(message(MessageType.CREATE_ROOM), ErrorCode.INVALID_REQUEST, "Bad nickname");
        assertEquals(ID, error.requestId());
        assertInstanceOf(Payloads.ErrorPayload.class, PayloadCodec.decode(error));
        assertFalse(((Payloads.ErrorPayload) PayloadCodec.decode(ProtocolMessage.error(
                message(MessageType.CREATE_ROOM), ErrorCode.SERVER_BUSY, "Busy"))).retryable());
        assertTrue(((Payloads.ErrorPayload) PayloadCodec.decode(ProtocolMessage.error(
                message(MessageType.CREATE_ROOM), ErrorCode.SERVER_BUSY, "Not admitted", true))).retryable());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void retryableIsNotInferredFromErrorCode(ErrorCode code) throws Exception {
        for (boolean safeToRetry : new boolean[]{false, true}) {
            ProtocolMessage error = ProtocolMessage.error(message(MessageType.CREATE_ROOM), code, "Context decides", safeToRetry);
            Payloads.ErrorPayload payload = (Payloads.ErrorPayload) PayloadCodec.decode(json.decode(json.encode(error)));
            assertEquals(safeToRetry, payload.retryable());
            assertEquals(ID, error.requestId());
        }
    }

    @Test
    void telemetryDoesNotImposeExperimentCeilingsOnWireValues() throws Exception {
        Payloads.ClientMetrics metrics = new Payloads.ClientMetrics(1, 120000, 180000, 90000.5, -7200000);
        ProtocolMessage report = PayloadCodec.message(MessageType.CLIENT_METRICS, ID, "r1", metrics);
        assertEquals(metrics, PayloadCodec.decode(json.decode(json.encode(report))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "null"})
    void retryableStillRequiresAnActualBoolean(String value) {
        String raw = "{\"type\":\"ERROR\",\"protocolVersion\":1,\"requestId\":\"" + ID
                + "\",\"payload\":{\"code\":\"SERVER_BUSY\",\"message\":\"Busy\",\"retryable\":" + value + "}}";
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(json.decode(raw.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void staleStateRecoveryExamplesUseANewLogicalCommandNotATransportRetry() throws Exception {
        ProtocolMessage original = message(MessageType.PLAY);
        ProtocolMessage rejection = PayloadCodec.message(MessageType.ERROR, original.requestId(), "r1",
                new Payloads.ErrorPayload(ErrorCode.STALE_STATE, "Refresh state", false, STATE.roomVersion() + 1));
        var recovered = new vn.edu.music.common.model.RoomState(STATE.roomId(), STATE.roomCode(), STATE.hostClientId(),
                STATE.participants(), STATE.currentTrackId(), STATE.playbackState(), STATE.positionMs(),
                STATE.anchorServerTimeMs(), STATE.roomVersion() + 1, STATE.streamEpoch(), STATE.queue());
        ProtocolMessage push = PayloadCodec.message(MessageType.ROOM_STATE, java.util.UUID.randomUUID().toString(), "r1",
                new Payloads.MembershipState("c1", recovered, List.of(TRACK), TIME));
        ProtocolMessage next = PayloadCodec.message(MessageType.PLAY, java.util.UUID.randomUUID().toString(), "r1",
                new Payloads.PlaybackCommand(recovered.roomVersion()));
        assertEquals(original.requestId(), rejection.requestId());
        assertNotEquals(original.requestId(), push.requestId());
        assertNotEquals(original.requestId(), next.requestId());
        assertEquals(((Payloads.ErrorPayload) PayloadCodec.decode(rejection)).currentRoomVersion().longValue(),
                ((Payloads.MembershipState) PayloadCodec.decode(push)).state().roomVersion());
        assertEquals(recovered.roomVersion(), ((Payloads.PlaybackCommand) PayloadCodec.decode(next)).expectedRoomVersion());
        ProtocolMessage retry = json.decode(json.encode(original));
        assertEquals(original.requestId(), retry.requestId());
        assertEquals(original.type(), retry.type());
        assertEquals(original.protocolVersion(), retry.protocolVersion());
        assertEquals(original.roomId(), retry.roomId());
        // A03 uses logical numeric values, not raw Map.equals(Long versus Integer).
        assertEquals(PayloadCodec.decode(original), PayloadCodec.decode(retry));
        assertArrayEquals(json.encode(original), json.encode(retry));
    }

    @Test
    void messageMappingsPreserveAllExistingTypes() {
        assertEquals(22, MessageType.values().length);
        assertEquals(11, Arrays.stream(MessageType.values()).filter(MessageType::isClientRequest).count());
        assertEquals(8, Arrays.stream(MessageType.values()).filter(MessageType::isStateChangingRequest).count());
        assertEquals(MessageType.LEAVE_ACK, MessageType.LEAVE_ROOM.responseType());
        assertEquals(MessageType.METRICS_ACK, MessageType.CLIENT_METRICS.responseType());
        assertTrue(MessageType.SYNC_STATE.canBeServerPush());
        assertFalse(MessageType.AUDIO_CHUNK.canBeServerPush());
    }

    @Test
    void nestedPayloadIsImmutableForFutureUiReaderHandoff() {
        List<Object> ids = new ArrayList<>(List.of("one"));
        Map<String, Object> nested = new HashMap<>(Map.of("ids", ids));
        ProtocolMessage message = ProtocolMessage.request(MessageType.PING, null, Map.of("nested", nested));
        ids.clear(); nested.clear();
        Map<?, ?> copy = (Map<?, ?>) message.payload().get("nested");
        assertEquals(List.of("one"), copy.get("ids"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) copy.get("ids")).clear());
    }

    @Test
    void cyclicOrMutableNonJsonPayloadIsRejected() {
        Map<String, Object> cyclic = new HashMap<>(); cyclic.put("cycle", cyclic);
        assertThrows(IllegalArgumentException.class, () -> ProtocolMessage.request(MessageType.PING, null, cyclic));
        assertThrows(IllegalArgumentException.class, () -> ProtocolMessage.request(MessageType.PING, null,
                Map.of("number", new java.util.concurrent.atomic.AtomicInteger(1))));
    }

    @Test
    void enumOrdinalIsNotAValidWireType() {
        String numericType = "{\"type\":1,\"protocolVersion\":1,\"requestId\":\"" + ID + "\",\"payload\":{}}";
        assertThrows(java.io.IOException.class, () -> json.decode(numericType.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> state = ContractJson.mapper().convertValue(STATE,
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        state.put("playbackState", 0);
        assertInvalid(MessageType.PLAYBACK_STATE, "r1", Map.of("state", state, "serverTimeMs", TIME));
    }

    @Test
    void documentedJsonExamplesCoverEveryControlMessage() throws Exception {
        JsonNode examples = ContractJson.mapper().readTree(Files.readString(Path.of("docs/examples/control-v1.json")));
        Set<MessageType> found = EnumSet.noneOf(MessageType.class);
        for (JsonNode example : examples) {
            ProtocolMessage decoded = json.decode(example.toString().getBytes(StandardCharsets.UTF_8));
            PayloadCodec.decode(decoded);
            assertTrue(found.add(decoded.type()), "Duplicate example for " + decoded.type());
        }
        Set<MessageType> expected = EnumSet.allOf(MessageType.class); expected.remove(MessageType.AUDIO_CHUNK);
        assertEquals(expected, found);
    }

    @Test
    void documentedInvalidExamplesAreRejected() throws Exception {
        JsonNode examples = ContractJson.mapper().readTree(Files.readString(Path.of("docs/examples/invalid-control-v1.json")));
        for (JsonNode example : examples) {
            byte[] raw = example.get("message").toString().getBytes(StandardCharsets.UTF_8);
            assertThrows(java.io.IOException.class, () -> PayloadCodec.decode(json.decode(raw)), example.get("description").asText());
        }
    }

    private void assertInvalid(MessageType type, String roomId, Map<String, Object> payload) {
        assertThrows(ProtocolException.class,
                () -> PayloadCodec.decode(new ProtocolMessage(type, 1, ID, roomId, payload)));
    }
}
