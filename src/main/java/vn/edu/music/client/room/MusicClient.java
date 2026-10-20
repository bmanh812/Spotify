package vn.edu.music.client.room;

import vn.edu.music.client.network.*;
import vn.edu.music.common.protocol.*;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Application gateway. Future completions happen after the ordered state-apply callback. */
public final class MusicClient implements AutoCloseable {
    private final RoomStateStore rooms = new RoomStateStore();
    private final CopyOnWriteArrayList<Consumer<RoomStateStore.View>> observers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ProtocolClient.Incoming>> incomingObservers = new CopyOnWriteArrayList<>();
    private ProtocolClient transport;
    private volatile Throwable disconnectCause;

    private MusicClient() { }

    public static MusicClient connect(String host, int port, ClientSettings settings) throws IOException {
        MusicClient client = new MusicClient();
        client.transport = ProtocolClient.connect(host, port, settings, new ProtocolClient.Listener() {
            @Override public void onIncoming(ProtocolClient.Incoming in) throws IOException {
                if (in.control() != null && client.rooms.accept(in.control(), in.request())) client.publish();
                // Adapters must return promptly; they enqueue work on their own audio/policy worker.
                for (var observer : client.incomingObservers) observer.accept(in);
            }
            @Override public void onDisconnected(Throwable cause) {
                client.disconnectCause = cause;
                client.rooms.disconnected(); client.publish();
            }
        });
        return client;
    }

    public RoomStateStore.View view() { return rooms.view(); }
    public boolean isConnected() { return transport != null && !transport.isClosed(); }
    public Throwable disconnectCause() { return disconnectCause; }
    public void observe(Consumer<RoomStateStore.View> observer) { observers.add(observer); observer.accept(view()); }
    public void observeIncoming(Consumer<ProtocolClient.Incoming> observer) { incomingObservers.add(observer); }

    private void publish() {
        var snapshot = rooms.view();
        for (var observer : observers) observer.accept(snapshot);
    }

    public CompletableFuture<ProtocolClient.Incoming> create(String nickname) {
        return lifecycle(MessageType.CREATE_ROOM, null, new Payloads.CreateRoom(nickname));
    }

    public CompletableFuture<ProtocolClient.Incoming> join(String nickname, String code) {
        return lifecycle(MessageType.JOIN_ROOM, null, new Payloads.JoinRoom(nickname, code));
    }

    public CompletableFuture<ProtocolClient.Incoming> leave() {
        var state = view().state();
        if (state == null) return CompletableFuture.failedFuture(new IllegalStateException("Not in a room"));
        return lifecycle(MessageType.LEAVE_ROOM, state.roomId(), new Payloads.LeaveRoom());
    }

    private CompletableFuture<ProtocolClient.Incoming> lifecycle(MessageType type, String room, Payloads.Payload payload) {
        try {
            var request = PayloadCodec.message(type, UUID.randomUUID().toString(), room, payload);
            rooms.beginLifecycle(request); publish();
            return send(request);
        } catch (RuntimeException | ProtocolException e) { return CompletableFuture.failedFuture(e); }
    }

    public CompletableFuture<ProtocolClient.Incoming> addTrack(String trackId) {
        var view = view();
        if (view.state() == null) return CompletableFuture.failedFuture(new IllegalStateException("Not in a room"));
        return command(MessageType.QUEUE_ADD, new Payloads.QueueAdd(trackId, view.state().roomVersion()));
    }

    public CompletableFuture<ProtocolClient.Incoming> playback(MessageType type, long positionMs) {
        var view = view();
        if (!view.isHost()) return CompletableFuture.failedFuture(new IllegalStateException("Only Host controls playback"));
        if (type != MessageType.PLAY && type != MessageType.PAUSE && type != MessageType.SEEK && type != MessageType.NEXT_TRACK) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a playback command"));
        }
        Payloads.Payload payload = type == MessageType.SEEK
                ? new Payloads.Seek(positionMs, view.state().roomVersion())
                : new Payloads.PlaybackCommand(view.state().roomVersion());
        return command(type, payload);
    }

    /** Read-only audio request for the TV1 audio adapter; no automatic prefetch policy here. */
    public CompletableFuture<ProtocolClient.Incoming> chunk(long index) {
        var state = view().state();
        if (state == null || state.currentTrackId() == null) return CompletableFuture.failedFuture(new IllegalStateException("No current track"));
        return command(MessageType.AUDIO_CHUNK_REQUEST, new Payloads.ChunkRequest(state.currentTrackId(), state.streamEpoch(), index));
    }

    /** TV3 receives t1 echo, monotonic send and t4 receipt via Incoming; no RTT estimator in TV1. */
    public CompletableFuture<ProtocolClient.Incoming> ping() {
        try {
            return send(PayloadCodec.message(MessageType.PING, UUID.randomUUID().toString(), null,
                    new Payloads.Ping(System.currentTimeMillis())));
        } catch (ProtocolException e) { return CompletableFuture.failedFuture(e); }
    }

    private CompletableFuture<ProtocolClient.Incoming> command(MessageType type, Payloads.Payload payload) {
        var view = view();
        if (view.state() == null || view.lifecyclePending() || view.uncertain()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Room unavailable or outcome UNKNOWN; disconnect to recover"));
        }
        try { return send(PayloadCodec.message(type, UUID.randomUUID().toString(), view.state().roomId(), payload)); }
        catch (ProtocolException e) { return CompletableFuture.failedFuture(e); }
    }

    private CompletableFuture<ProtocolClient.Incoming> send(ProtocolMessage request) {
        return transport.request(request).whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                rooms.failed(request, cause instanceof TimeoutException || cause instanceof CancellationException);
                publish();
            }
        });
    }

    @Override public void close() { transport.close(); rooms.disconnected(); }
}
