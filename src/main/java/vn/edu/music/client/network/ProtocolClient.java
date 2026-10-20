package vn.edu.music.client.network;

import vn.edu.music.common.protocol.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One TCP session. Listener and future continuations must not block the ordered event worker. */
public final class ProtocolClient implements AutoCloseable {
    public record Incoming(ProtocolMessage control, AudioChunk audio, ProtocolMessage request,
                           long receivedEpochMs, long receivedNanos, long sentNanos) { }

    public interface Listener {
        void onIncoming(Incoming incoming) throws IOException;
        default void onDisconnected(Throwable cause) { }
    }

    private static final class Pending {
        final ProtocolMessage request;
        final CompletableFuture<Incoming> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeout;
        volatile long sentNanos;
        volatile ProtocolMessage transmitted;
        Pending(ProtocolMessage request) { this.request = request; this.transmitted = request; }
    }

    private final Socket socket;
    private final ClientSettings settings;
    private final Listener listener;
    private final FrameCodec frames = new FrameCodec(ProtocolLimits.MAX_FRAME_BYTES);
    private final JsonMessageCodec json = new JsonMessageCodec();
    private final AudioChunkCodec audio = new AudioChunkCodec();
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<String> usedIds = new HashSet<>();
    private final ArrayBlockingQueue<Pending> outbound;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ThreadPoolExecutor events;
    private final Thread reader;
    private final Thread writer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ProtocolClient(Socket socket, ClientSettings settings, Listener listener) {
        this.socket = socket;
        this.settings = settings;
        this.listener = Objects.requireNonNull(listener);
        outbound = new ArrayBlockingQueue<>(settings.outboundCapacity());
        deadlines = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "client-deadlines"));
        deadlines.setRemoveOnCancelPolicy(true);
        events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.eventCapacity()), r -> new Thread(r, "client-events"));
        reader = new Thread(this::readLoop, "client-reader");
        writer = new Thread(this::writeLoop, "client-writer");
        writer.start();
        reader.start();
    }

    /** Call from a background worker, never the Swing EDT. */
    public static ProtocolClient connect(String host, int port, ClientSettings settings, Listener listener) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), settings.connectTimeoutMs());
            socket.setTcpNoDelay(true);
            return new ProtocolClient(socket, settings, listener);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    public synchronized CompletableFuture<Incoming> request(ProtocolMessage request) {
        try {
            ProtocolValidator.validateClientRequest(request);
            if (closed.get()) throw new IOException("Disconnected");
            if (pending.size() >= settings.maxPending()) throw new IOException("Client pending limit reached");
            if (request.type() == MessageType.AUDIO_CHUNK_REQUEST && pending.values().stream()
                    .filter(p -> p.request.type() == MessageType.AUDIO_CHUNK_REQUEST).count() >= settings.maxAudioPending()) {
                throw new IOException("Client audio request window full");
            }
            // Bounded session ledger. Reconnect explicitly after a long session; never evict IDs and silently replay a mutation.
            if (usedIds.size() >= 65536) throw new IOException("Session request budget reached; disconnect and connect again");
            if (!usedIds.add(request.requestId())) throw new IOException("requestId was already used; automatic retry is disabled");
            Pending item = new Pending(request);
            pending.put(request.requestId(), item);
            item.timeout = deadlines.schedule(() -> timeout(item), settings.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!outbound.offer(item)) {
                pending.remove(request.requestId(), item);
                item.timeout.cancel(false);
                item.future.completeExceptionally(new IOException("Client outbound queue full"));
            }
            item.future.whenComplete((value, error) -> {
                if (item.future.isCancelled() && pending.remove(request.requestId(), item)) {
                    item.timeout.cancel(false);
                    outbound.remove(item);
                }
            });
            return item.future;
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void timeout(Pending item) {
        if (pending.remove(item.request.requestId(), item)) {
            outbound.remove(item);
            item.future.completeExceptionally(new TimeoutException(
                    "Request timed out; mutation outcome may be UNKNOWN. No automatic retry."));
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                Pending item = outbound.take();
                if (pending.get(item.request.requestId()) != item) continue;
                if (item.request.type() == MessageType.PING) {
                    item.transmitted = PayloadCodec.message(MessageType.PING, item.request.requestId(), null,
                            new Payloads.Ping(System.currentTimeMillis()));
                }
                item.sentNanos = System.nanoTime();
                byte[] bytes = json.encode(item.transmitted);
                frames.write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON, bytes));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) { terminate(e); }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                Frame frame = frames.read(socket.getInputStream());
                if (frame == null) throw new EOFException("Server disconnected");
                long receivedNanos = System.nanoTime();
                long receivedEpochMs = System.currentTimeMillis();
                ProtocolMessage control = null;
                AudioChunk chunk = null;
                if (frame.type() == FrameType.CONTROL_JSON) {
                    control = json.decode(frame.payload());
                    ProtocolValidator.validateServerMessage(control);
                } else { chunk = audio.decode(frame); }
                ProtocolMessage message = control;
                AudioChunk audioChunk = chunk;
                events.execute(() -> deliver(message, audioChunk, receivedEpochMs, receivedNanos));
            }
        } catch (IOException | RuntimeException e) {
            // Complete frames already queued must be delivered before a clean EOF notification.
            if (e instanceof EOFException) {
                try { events.execute(() -> terminate(e)); }
                catch (RejectedExecutionException rejected) { terminate(e); }
            } else { terminate(e); }
        }
    }

    private void deliver(ProtocolMessage control, AudioChunk chunk, long epochMs, long nanos) {
        if (closed.get()) return;
        String id = control != null ? control.requestId() : chunk.metadata().requestId();
        Pending item = pending.get(id);
        try {
            if (item != null) {
                MessageType actual = control != null ? control.type() : MessageType.AUDIO_CHUNK;
                if (actual != MessageType.ERROR && actual != item.request.type().responseType()) {
                    throw new ProtocolException("Response type does not match request");
                }
                String room = control != null ? control.roomId() : chunk.metadata().roomId();
                boolean membership = item.request.type() == MessageType.CREATE_ROOM || item.request.type() == MessageType.JOIN_ROOM;
                if ((!membership || actual == MessageType.ERROR) && !Objects.equals(room, item.request.roomId())) {
                    throw new ProtocolException("Response room scope mismatch");
                }
                if (chunk != null) {
                    Payloads.ChunkRequest p = (Payloads.ChunkRequest) PayloadCodec.decode(item.request);
                    var m = chunk.metadata();
                    if (!m.trackId().equals(p.trackId()) || m.streamEpoch() != p.streamEpoch() || m.chunkIndex() != p.chunkIndex()) {
                        throw new ProtocolException("Audio response context mismatch");
                    }
                }
                if (control != null && control.type() == MessageType.PONG) {
                    var ping = (Payloads.Ping) PayloadCodec.decode(item.transmitted);
                    var pong = (Payloads.Pong) PayloadCodec.decode(control);
                    if (ping.clientSendTimeMs() != pong.clientSendTimeMs()) throw new ProtocolException("PONG echo mismatch");
                }
                if (!pending.remove(id, item)) item = null; // Deadline won; do not complete or establish a lifecycle.
            }
            if (item == null && (control == null || !control.type().canBeServerPush())) return;
            if (item == null && control != null) {
                synchronized (this) { if (usedIds.contains(id)) return; } // Late direct response is not a push.
            }
            Incoming incoming = new Incoming(control, chunk, item == null ? null : item.transmitted,
                    epochMs, nanos, item == null ? 0 : item.sentNanos);
            if (item != null) item.timeout.cancel(false);
            try {
                listener.onIncoming(incoming);
                if (item != null) {
                    if (control != null && control.type() == MessageType.ERROR) {
                        item.future.completeExceptionally(new ServerErrorException((Payloads.ErrorPayload) PayloadCodec.decode(control)));
                    } else { item.future.complete(incoming); }
                }
            } catch (IOException | RuntimeException e) {
                if (item != null) item.future.completeExceptionally(e);
                throw e;
            }
        } catch (IOException | RuntimeException e) { terminate(e); }
    }

    private void terminate(Throwable cause) {
        List<Pending> abandoned;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            abandoned = List.copyOf(pending.values());
            pending.clear();
        }
        try { socket.close(); } catch (IOException ignored) { }
        writer.interrupt();
        deadlines.shutdownNow();
        outbound.clear();
        abandoned.forEach(item -> item.future.completeExceptionally(cause));
        events.getQueue().clear();
        try { events.execute(() -> listener.onDisconnected(cause)); }
        catch (RejectedExecutionException ignored) { listener.onDisconnected(cause); }
        events.shutdown();
    }

    public boolean isClosed() { return closed.get(); }
    public int pendingCount() { return pending.size(); }

    /** Background/test cleanup barrier. Do not call from listener callbacks or the Swing EDT. */
    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 1 || timeoutMs > 60000) throw new IllegalArgumentException("Timeout must be 1..60000 ms");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (Thread thread : new Thread[]{reader, writer}) {
            if (thread == Thread.currentThread()) return false;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !events.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return false;
        remaining = deadline - System.nanoTime();
        return remaining > 0 && deadlines.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                && !reader.isAlive() && !writer.isAlive();
    }

    @Override public void close() { terminate(new IOException("Client disconnected")); }
}
