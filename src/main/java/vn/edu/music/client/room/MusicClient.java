package vn.edu.music.client.room;

import vn.edu.music.client.network.*;
import vn.edu.music.client.audio.*;
import vn.edu.music.common.model.PlaybackState;
import vn.edu.music.common.protocol.*;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Application gateway. Future completions happen after the ordered state-apply callback. */
public final class MusicClient implements AutoCloseable {
    private final Object stateGate = new Object();
    private final RoomStateStore rooms = new RoomStateStore();
    private final CopyOnWriteArrayList<Consumer<RoomStateStore.View>> observers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ProtocolClient.Incoming>> incomingObservers = new CopyOnWriteArrayList<>();
    private final AudioRuntime audio;
    private volatile ProtocolClient transport;
    private volatile Throwable disconnectCause;

    private MusicClient(AudioSink sink, AudioRuntime.Settings audioSettings) { audio = new AudioRuntime(sink, audioSettings); }

    public static MusicClient connect(String host, int port, ClientSettings settings) throws IOException {
        return connect(host, port, settings, new SourceDataLineSink(), AudioRuntime.Settings.defaults());
    }

    /** The sink is injectable; connecting never opens an audio device. */
    public static MusicClient connect(String host, int port, ClientSettings settings, AudioSink sink,
                                      AudioRuntime.Settings audioSettings) throws IOException {
        MusicClient client = new MusicClient(sink, audioSettings);
        try {
            client.transport = ProtocolClient.connect(host, port, settings, new ProtocolClient.Listener() {
            @Override public void onIncoming(ProtocolClient.Incoming in) throws IOException {
                synchronized (client.stateGate) {
                    if (in.control() != null && client.rooms.accept(in.control(), in.request())) {
                        client.synchronizeAudio(); client.publish();
                    }
                }
                // Adapters must return promptly; they enqueue work on their own audio/policy worker.
                for (var observer : client.incomingObservers) observer.accept(in);
            }
            @Override public void onDisconnected(Throwable cause) {
                client.disconnectCause = cause;
                synchronized (client.stateGate) {
                    client.audio.close(); client.rooms.disconnected(); client.publish();
                }
            }
            });
            return client;
        } catch (IOException | RuntimeException e) { client.audio.close(); throw e; }
    }

    public RoomStateStore.View view() { synchronized (stateGate) { return rooms.view(); } }
    public boolean isConnected() { return transport != null && !transport.isClosed(); }
    public Throwable disconnectCause() { return disconnectCause; }
    public AutoCloseable observe(Consumer<RoomStateStore.View> observer) {
        synchronized (stateGate) { observers.add(observer); observer.accept(rooms.view()); }
        return () -> observers.remove(observer);
    }
    public AutoCloseable observeIncoming(Consumer<ProtocolClient.Incoming> observer) {
        incomingObservers.add(observer); return () -> incomingObservers.remove(observer);
    }
    public AudioRuntime audio() { return audio; }
    public int pendingRequestCount() { return transport.pendingCount(); }

    private void synchronizeAudio() {
        var view = rooms.view(); var state = view.state();
        if (state == null || state.currentTrackId() == null || view.uncertain()) { audio.updateScope(null); return; }
        var track = view.tracks().stream().filter(t -> t.trackId().equals(state.currentTrackId())).findFirst().orElseThrow();
        audio.updateScope(new AudioRuntime.StreamScope(state.roomId(), track, state.streamEpoch()));
        var token = audio.latest().token();
        if (state.playbackState() == PlaybackState.PAUSED && token != null) audio.pause(token);
        // PLAYING needs a valid TV3 clock/plan. It never triggers immediate playback here.
    }

    private void publish() {
        synchronized (stateGate) {
            var snapshot = rooms.view();
            for (var observer : observers) observer.accept(snapshot);
        }
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
            synchronized (stateGate) { rooms.beginLifecycle(request); publish(); }
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
        var view = view(); var state = view.state();
        if (state == null || state.currentTrackId() == null) return CompletableFuture.failedFuture(new IllegalStateException("No current track"));
        if (view.lifecyclePending() || view.uncertain()) return CompletableFuture.failedFuture(new IllegalStateException("Room unavailable"));
        try { return send(PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, UUID.randomUUID().toString(), state.roomId(),
                new Payloads.ChunkRequest(state.currentTrackId(), state.streamEpoch(), index))); }
        catch (ProtocolException e) { return CompletableFuture.failedFuture(e); }
    }

    /** Explicit read with a captured audio generation. Does not choose the next index or start playback. */
    public AudioRequest requestAudio(AudioRuntime.Token token, long index) throws ProtocolException {
        var view = view(); var state = view.state();
        if (state == null || view.lifecyclePending() || view.uncertain() || token == null || !audio.isCurrent(token)
                || !state.roomId().equals(token.scope().roomId()) || !java.util.Objects.equals(state.currentTrackId(), token.scope().track().trackId())
                || state.streamEpoch() != token.scope().streamEpoch()) throw new IllegalStateException("Audio stream changed");
        String requestId = UUID.randomUUID().toString();
        var scope = token.scope();
        var request = PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, requestId, scope.roomId(),
                new Payloads.ChunkRequest(scope.track().trackId(), scope.streamEpoch(), index));
        AudioRequest handle = audio.reserveRequest(token, index, requestId);
        if (!handle.response().isCancelled()) handle.attach(send(request));
        return handle;
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
        var source = transport.request(request);
        CompletableFuture<ProtocolClient.Incoming> exposed = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                if (!source.cancel(false)) return false;
                return super.cancel(false);
            }
        };
        source.whenComplete((result, error) -> {
            try {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                    try {
                        synchronized (stateGate) {
                            rooms.failed(request, cause instanceof TimeoutException || cause instanceof CancellationException);
                            synchronizeAudio(); publish();
                        }
                    }
                    finally { exposed.completeExceptionally(cause); }
                } else exposed.complete(result);
            } catch (RuntimeException callbackError) { exposed.completeExceptionally(callbackError); }
        });
        return exposed;
    }

    @Override public void close() {
        synchronized (stateGate) { audio.close(); transport.close(); rooms.disconnected(); }
    }

    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Cleanup wait on EDT");
        if (timeoutMs < 1 || timeoutMs > 60000) throw new IllegalArgumentException("Invalid cleanup timeout");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (!transport.awaitTermination(timeoutMs)) return false;
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        return remaining > 0 && audio.awaitTermination(remaining);
    }
}
