package vn.edu.music.common.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.edu.music.common.model.*;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.common.protocol.ContractFixtures.*;

class RoomStateContractTest {
    private final ObjectMapper mapper = ContractJson.mapper();

    @Test
    void roomStateAndTrackCatalogRoundTripWithoutServerPackageDependency() throws Exception {
        Payloads.MembershipState decoded = (Payloads.MembershipState) PayloadCodec.decode(message(MessageType.ROOM_STATE));
        assertEquals(STATE, decoded.state());
        assertEquals(List.of(TRACK), decoded.tracks());
        assertEquals("c1", decoded.clientId());
    }

    @TestFactory
    Stream<DynamicTest> allRoomStateFieldsRequiredIncludingNullableCurrentTrack() {
        return Arrays.stream(RoomState.class.getRecordComponents()).map(field -> DynamicTest.dynamicTest(
                "RoomState missing " + field.getName(), () -> {
                    Map<String, Object> state = map(STATE); state.remove(field.getName());
                    assertBadState(state);
                }));
    }

    @TestFactory
    Stream<DynamicTest> allTrackMetadataFieldsRequired() {
        return Arrays.stream(TrackMetadata.class.getRecordComponents()).map(field -> DynamicTest.dynamicTest(
                "TrackMetadata missing " + field.getName(), () -> {
                    Map<String, Object> track = map(TRACK); track.remove(field.getName());
                    Map<String, Object> membership = map(new Payloads.MembershipState("c1", STATE, List.of(TRACK), TIME));
                    membership.put("tracks", List.of(track));
                    assertThrows(ProtocolException.class, () -> PayloadCodec.decode(
                            new ProtocolMessage(MessageType.ROOM_STATE, 1, ID, "r1", membership)));
                }));
    }

    @Test
    void validatesInitialEmptyRoomAndFutureScheduledAnchor() throws Exception {
        RoomState initial = new RoomState("r1", "ABC123", "c1", STATE.participants(), null,
                PlaybackState.PAUSED, 0, TIME, 1, 0, List.of());
        ProtocolValidator.validateRoomState(initial);
        ProtocolValidator.validateRoomState(new RoomState("r1", "ABC123", "c1", STATE.participants(), "t1",
                PlaybackState.PLAYING, 500, TIME + 4500, 2, 1, List.of()));
        assertEquals(0, initial.streamEpoch());
    }

    @Test
    void rejectsInvalidMembershipIdsVersionsAndNoTrackPlayback() {
        assertBadState(changed("participants", List.of()));
        assertBadState(changed("participants", List.of(map(STATE.participants().getFirst()), map(STATE.participants().getFirst()))));
        assertBadState(changed("hostClientId", "outsider"));
        assertBadState(changed("roomVersion", 0));
        assertBadState(changed("streamEpoch", -1));
        assertBadState(changed("streamEpoch", 0)); // Selected track needs epoch >= 1.
        assertBadState(changed("currentTrackId", null)); // PLAYING without a track.
        assertBadState(changed("positionMs", -1));
        assertBadState(changed("anchorServerTimeMs", 0));
    }

    @Test
    void roomCreatedCannotPretendAnExistingPlaybackSessionIsNew() {
        assertThrows(ProtocolException.class, () -> PayloadCodec.message(MessageType.ROOM_CREATED, ID, "r1",
                new Payloads.MembershipState("c1", STATE, List.of(TRACK), TIME)));
    }

    @Test
    void pausedAnchorCannotBeInFutureOfSnapshotTimestamp() {
        RoomState invalid = new RoomState("r1", "ABC123", "c1", STATE.participants(), null,
                PlaybackState.PAUSED, 0, TIME + 1, 1, 0, List.of());
        assertThrows(ProtocolException.class, () -> PayloadCodec.message(MessageType.ROOM_STATE, ID, "r1",
                new Payloads.MembershipState("c1", invalid, List.of(TRACK), TIME)));
    }

    @Test
    void rejectsDuplicateQueueIdsButAllowsDepartedAdder() throws Exception {
        QueueItem item = new QueueItem("q1", "t1", "departed-client");
        Map<String, Object> state = changed("queue", List.of(map(item), map(item)));
        assertBadState(state);
        RoomState allowed = new RoomState("r1", "ABC123", "c1", STATE.participants(), "t1",
                PlaybackState.PLAYING, 0, TIME, 2, 1, List.of(item));
        ProtocolValidator.validateRoomState(allowed);
    }

    @Test
    void contextualCatalogRejectsUnknownTrackAndBeyondDuration() throws Exception {
        RoomState unknown = new RoomState("r1", "ABC123", "c1", STATE.participants(), "unknown",
                PlaybackState.PAUSED, 0, TIME, 2, 1, List.of());
        assertThrows(ProtocolException.class, () -> ProtocolValidator.validateStateAgainstCatalog(unknown, List.of(TRACK)));
        RoomState pastEnd = new RoomState("r1", "ABC123", "c1", STATE.participants(), "t1",
                PlaybackState.PAUSED, 1001, TIME, 2, 1, List.of());
        assertThrows(ProtocolException.class, () -> ProtocolValidator.validateStateAgainstCatalog(pastEnd, List.of(TRACK)));
        assertThrows(ProtocolException.class, () -> ProtocolValidator.validateCatalog(List.of(TRACK, TRACK)));
    }

    @Test
    void validatesPcmMetadataAndRejectsInconsistentDerivedValues() throws Exception {
        ProtocolValidator.validateTrack(TRACK);
        for (var entry : Map.<String, Object>of("sampleRate", 48000, "channels", 1, "frameSizeBytes", 2,
                "sampleSizeBits", 24, "durationMs", 999, "totalPcmBytes", 1,
                "chunkFrames", 16000, "chunkCount", 3, "totalFrames", Long.MAX_VALUE).entrySet()) {
            Map<String, Object> invalid = map(TRACK); invalid.put(entry.getKey(), entry.getValue());
            TrackMetadata track = mapper.convertValue(invalid, TrackMetadata.class);
            assertThrows(ProtocolException.class, () -> ProtocolValidator.validateTrack(track), entry.getKey());
        }
    }

    @ParameterizedTest
    @EnumSource(PlaybackState.class)
    void currentTrackCannotBePublishedAtPermanentEof(PlaybackState playback) {
        RoomState eof = new RoomState("r1", "ABC123", "c1", STATE.participants(), "t1",
                playback, 1000, TIME, 3, 1, List.of());
        assertThrows(ProtocolException.class, () -> ProtocolValidator.validateStateAgainstCatalog(eof, List.of(TRACK)));
        assertThrows(ProtocolException.class, () -> PayloadCodec.message(MessageType.ROOM_STATE, ID, "r1",
                new Payloads.MembershipState("c1", eof, List.of(TRACK), TIME)));
    }

    @Test
    void admissionDefaultsAreNotSharedSchemaLimits() throws Exception {
        List<Participant> participants = new ArrayList<>();
        for (int i = 1; i <= 11; i++) participants.add(new Participant("c" + i, "Member " + i));
        List<QueueItem> queue = new ArrayList<>();
        for (int i = 0; i < 101; i++) queue.add(new QueueItem("q" + i, "t1", "c1"));
        List<TrackMetadata> catalog = new ArrayList<>();
        for (int i = 1; i <= 21; i++) catalog.add(new TrackMetadata("t" + i, "Track " + i,
                TRACK.durationMs(), 44100, 2, 16, 4, TRACK.totalFrames(), TRACK.totalPcmBytes(), 11025, 4));
        RoomState state = new RoomState("r1", "ABC123", "c1", participants, "t1",
                PlaybackState.PAUSED, 0, TIME, 5, 1, queue);
        ProtocolMessage message = PayloadCodec.message(MessageType.ROOM_STATE, ID, "r1",
                new Payloads.MembershipState("c1", state, catalog, TIME));
        byte[] bytes = new JsonMessageCodec().encode(message);
        assertTrue(bytes.length < ProtocolLimits.MAX_FRAME_BYTES);
        Payloads.MembershipState decoded = (Payloads.MembershipState) PayloadCodec.decode(new JsonMessageCodec().decode(bytes));
        assertEquals(11, decoded.state().participants().size());
        assertEquals(101, decoded.state().queue().size());
        assertEquals(21, decoded.tracks().size());
    }

    @Test
    void fullSnapshotStillMustFitActualWireCeiling() {
        List<QueueItem> queue = new ArrayList<>();
        for (int i = 0; i < 1000; i++) queue.add(new QueueItem("q" + i + "x".repeat(55), "t1", "c1"));
        RoomState oversized = new RoomState("r1", "ABC123", "c1", STATE.participants(), "t1",
                PlaybackState.PLAYING, 0, TIME, 2, 1, queue);
        ProtocolException error = assertThrows(ProtocolException.class, () -> PayloadCodec.message(
                MessageType.PLAYBACK_STATE, ID, "r1", new Payloads.StateUpdate(oversized, TIME)));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, error.code());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return mapper.convertValue(value, LinkedHashMap.class); }
    private Map<String, Object> changed(String field, Object value) {
        Map<String, Object> state = map(STATE); state.put(field, value); return state;
    }
    private void assertBadState(Map<String, Object> state) {
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(new ProtocolMessage(
                MessageType.PLAYBACK_STATE, 1, ID, "r1", Map.of("state", state, "serverTimeMs", TIME))));
    }
}
