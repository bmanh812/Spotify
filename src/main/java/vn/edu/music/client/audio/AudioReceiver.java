package vn.edu.music.client.audio;

import vn.edu.music.client.network.ProtocolClient;
import vn.edu.music.client.room.RoomStateStore;
import vn.edu.music.common.protocol.*;
import java.util.function.Supplier;

/** Called by an audio worker after enqueueing Incoming together with the generation captured for that request. */
public final class AudioReceiver {
    private final Supplier<RoomStateStore.View> room;
    private final PlaybackEngine engine;

    public AudioReceiver(Supplier<RoomStateStore.View> room, PlaybackEngine engine) {
        this.room = java.util.Objects.requireNonNull(room);
        this.engine = java.util.Objects.requireNonNull(engine);
    }

    public boolean accept(ProtocolClient.Incoming incoming, long requestGeneration) throws ProtocolException {
        if (incoming.audio() == null || incoming.request() == null) return false;
        var view = room.get(); var state = view.state(); var m = incoming.audio().metadata();
        if (state == null || !state.roomId().equals(m.roomId()) || !java.util.Objects.equals(state.currentTrackId(), m.trackId())
                || state.streamEpoch() != m.streamEpoch()) return false;
        var track = view.tracks().stream().filter(t -> t.trackId().equals(m.trackId())).findFirst()
                .orElseThrow(() -> new ProtocolException("Chunk references unknown catalog track"));
        AudioChunkCodec.validateAgainstTrack(m, track);
        return engine.offer(incoming.audio(), requestGeneration);
    }
}
