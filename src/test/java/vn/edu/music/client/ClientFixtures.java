package vn.edu.music.client;

import vn.edu.music.common.model.*;
import vn.edu.music.common.protocol.*;
import java.util.List;
import java.util.UUID;

public final class ClientFixtures {
    public static final long TIME = 1792000000000L;
    public static final TrackMetadata TRACK = new TrackMetadata("t1", "Test tone", 1000, 44100, 2, 16, 4,
            44100, 176400, 11025, 4);
    private ClientFixtures() { }
    public static String id() { return UUID.randomUUID().toString(); }
    public static RoomState state(long version, long epoch, String track) {
        return new RoomState("r1", "ABC123", "c1", List.of(new Participant("c1", "Host")), track,
                PlaybackState.PAUSED, 0, TIME, version, epoch, List.of());
    }
    public static ProtocolMessage membership(MessageType type, String id, RoomState state) throws ProtocolException {
        return PayloadCodec.message(type, id, state.roomId(), new Payloads.MembershipState("c1", state, List.of(TRACK), TIME));
    }
    public static ProtocolMessage update(RoomState state) throws ProtocolException {
        return PayloadCodec.message(MessageType.PLAYBACK_STATE, id(), state.roomId(), new Payloads.StateUpdate(state, TIME));
    }
    public static AudioChunk chunk(int index, long epoch) {
        byte[] bytes = new byte[44100];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ((i + index * 44100) % 127);
        return new AudioChunk(new AudioChunkMetadata(MessageType.AUDIO_CHUNK, 1, id(), "r1", "t1", epoch,
                index, index * 11025L, 11025, index == 3, bytes.length), bytes);
    }
}
