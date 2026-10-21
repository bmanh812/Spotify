package vn.edu.music.client.audio;

import vn.edu.music.common.model.TrackMetadata;
import vn.edu.music.common.protocol.*;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** TV1 device execution only. No clock estimator, prefetch policy or scheduled-start decision. */
public final class AudioRuntime implements AutoCloseable {
    public record Settings(int capacityMs, int commandCapacity, int pumpIntervalMs, int maxPumpFrames) {
        public Settings {
            if (capacityMs < 1 || capacityMs > 60000 || commandCapacity < 1 || commandCapacity > 4096
                    || pumpIntervalMs < 0 || pumpIntervalMs > 1000 || maxPumpFrames < 1 || maxPumpFrames > 15360) {
                throw new IllegalArgumentException("Invalid audio runtime limits");
            }
        }
        public static Settings defaults() { return new Settings(6000, 64, 10, 2048); }
    }

    public record StreamScope(String roomId, TrackMetadata track, long streamEpoch) {
        public StreamScope {
            if (roomId == null || !roomId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || streamEpoch < 1) {
                throw new IllegalArgumentException("Invalid stream scope");
            }
            Objects.requireNonNull(track);
        }
    }

    public record Token(long revision, long engineGeneration, StreamScope scope) { }
    public record State(long revision, Token token, PlaybackEngine.Snapshot engine, boolean closed, Throwable failure) { }
    @FunctionalInterface private interface Action<T> { T run() throws Exception; }

    private static final class Command<T> implements Runnable {
        final Action<T> action;
        final CompletableFuture<T> result = new CompletableFuture<>();
        Command(Action<T> action) { this.action = action; }
        @Override public void run() {
            try { result.complete(action.run()); }
            catch (Exception e) { result.completeExceptionally(e); }
        }
        void discard() { result.completeExceptionally(new CancellationException("Audio runtime closed")); }
    }

    private final Object gate = new Object();
    private final PlaybackEngine engine;
    private final Settings settings;
    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private final Map<Long, AudioRequest> requests = new HashMap<>();
    private volatile Thread audioThread;
    private volatile State latest;
    private StreamScope scope;
    private Token token;
    private long revision;
    private boolean closed;
    private volatile Throwable failure;

    public AudioRuntime(AudioSink sink, Settings settings) {
        this.settings = Objects.requireNonNull(settings);
        engine = new PlaybackEngine(sink, settings.capacityMs());
        latest = new State(0, null, engine.snapshot(), false, null);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.commandCapacity()), action -> {
                    Thread thread = new Thread(action, "client-audio"); audioThread = thread; return thread;
                });
        timer = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "client-audio-ticks"));
        timer.setRemoveOnCancelPolicy(true);
        if (settings.pumpIntervalMs() > 0) {
            timer.scheduleWithFixedDelay(this::enqueueTick, settings.pumpIntervalMs(), settings.pumpIntervalMs(), TimeUnit.MILLISECONDS);
        }
    }

    /** Membership/stream gate, safe to call from a short network callback. Same stream preserves the engine. */
    public CompletableFuture<Void> updateScope(StreamScope next) {
        List<AudioRequest> obsolete;
        CompletableFuture<Void> result;
        synchronized (gate) {
            if (closed) return rejected("Audio runtime closed");
            if (Objects.equals(scope, next)) return CompletableFuture.completedFuture(null);
            scope = next; revision++; token = null;
            obsolete = detachRequests();
            result = enqueueLocked(() -> {
                try { engine.unload(); capture(); return null; }
                catch (RuntimeException e) { fail(e); throw e; }
            });
        }
        obsolete.forEach(AudioRequest::cancel);
        failOnRejection(result);
        return result;
    }

    /** The caller supplies a frame chosen by Server state / TV3 policy. This method does not calculate a timeline. */
    public CompletableFuture<Token> reset(StreamScope expectedScope, long targetFrame) {
        try {
            Objects.requireNonNull(expectedScope);
            ProtocolValidator.validateTrack(expectedScope.track());
            if (targetFrame < 0 || targetFrame >= expectedScope.track().totalFrames()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Target frame outside track"));
            }
        } catch (ProtocolException | RuntimeException e) { return CompletableFuture.failedFuture(e); }
        List<AudioRequest> obsolete;
        CompletableFuture<Token> result;
        synchronized (gate) {
            if (closed || !Objects.equals(scope, expectedScope)) return rejected("Stream context changed");
            long expectedRevision = ++revision;
            token = null; obsolete = detachRequests();
            result = enqueueLocked(() -> {
                synchronized (gate) {
                    if (closed || revision != expectedRevision) throw new CancellationException("Reset superseded");
                }
                long generation;
                try { generation = engine.reset(expectedScope.roomId(), expectedScope.track(), expectedScope.streamEpoch(), targetFrame); }
                catch (Exception e) { fail(e); throw e; }
                Token prepared = new Token(expectedRevision, generation, expectedScope);
                boolean stale;
                synchronized (gate) {
                    stale = closed || revision != expectedRevision;
                    if (!stale) token = prepared;
                }
                if (stale) { engine.unload(); capture(); throw new CancellationException("Reset superseded"); }
                capture(); return prepared;
            });
        }
        obsolete.forEach(AudioRequest::cancel);
        failOnRejection(result);
        return result;
    }

    public CompletableFuture<Boolean> offer(Token expected, AudioChunk chunk) {
        synchronized (gate) {
            if (!isCurrentLocked(expected)) return CompletableFuture.completedFuture(false);
            return enqueueLocked(() -> {
                if (!isCurrent(expected)) return false;
                boolean accepted = engine.offer(chunk, expected.engineGeneration()); capture(); return accepted;
            });
        }
    }

    /** Prime device while stopped, or feed it while started. Timer coalesces automatic calls to this operation. */
    public CompletableFuture<Integer> pump(Token expected) {
        synchronized (gate) {
            if (!isCurrentLocked(expected)) return CompletableFuture.completedFuture(0);
            return enqueueLocked(() -> pumpOnWorker(expected));
        }
    }

    public CompletableFuture<Boolean> start(Token expected) { return control(expected, true); }
    public CompletableFuture<Boolean> pause(Token expected) { return control(expected, false); }

    private CompletableFuture<Boolean> control(Token expected, boolean start) {
        CompletableFuture<Boolean> result;
        synchronized (gate) {
            if (!isCurrentLocked(expected)) return CompletableFuture.completedFuture(false);
            result = enqueueLocked(() -> {
                if (!isCurrent(expected)) return false;
                try {
                    if (start) engine.start(expected.engineGeneration()); else engine.pause();
                    capture(); return true;
                } catch (RuntimeException e) { fail(e); throw e; }
            });
        }
        failOnRejection(result); return result;
    }

    /** Reserve before sending to avoid duplicate outstanding reads of the same chunk/generation. */
    public AudioRequest reserveRequest(Token expected, long index, String requestId) {
        synchronized (gate) {
            if (!isCurrentLocked(expected)) throw new IllegalStateException("Stale audio request context");
            if (index < 0 || index >= expected.scope().track().chunkCount()) throw new IllegalArgumentException("Chunk index outside track");
            if (requests.containsKey(index)) throw new IllegalStateException("Chunk already pending");
            if (requests.size() >= 64) throw new RejectedExecutionException("Audio handle budget reached");
            AudioRequest handle = new AudioRequest(requestId, index, expected);
            requests.put(index, handle);
            handle.response().whenComplete((value, error) -> {
                synchronized (gate) { requests.remove(index, handle); }
            });
            return handle;
        }
    }

    public boolean isCurrent(Token expected) { synchronized (gate) { return isCurrentLocked(expected); } }
    private boolean isCurrentLocked(Token expected) { return !closed && expected != null && expected.equals(token) && expected.revision() == revision; }
    public int pendingRequests() { synchronized (gate) { return requests.size(); } }
    public State latest() { return latest; }

    /** Fresh snapshot, sampled on the same worker that touches the device. */
    public CompletableFuture<State> snapshot() {
        synchronized (gate) {
            if (closed && worker.isTerminated()) return CompletableFuture.completedFuture(latest);
            return enqueueLocked(() -> { capture(); return latest; });
        }
    }

    private int pumpOnWorker(Token expected) {
        if (!isCurrent(expected)) return 0;
        try { int count = engine.pump(settings.maxPumpFrames(), expected.engineGeneration()); capture(); return count; }
        catch (RuntimeException e) { fail(e); throw e; }
    }

    private void enqueueTick() {
        synchronized (gate) {
            Token expected = token;
            if (closed || expected == null || !tickQueued.compareAndSet(false, true)) return;
            try {
                worker.execute(() -> {
                    try { pumpOnWorker(expected); }
                    catch (RuntimeException ignored) { /* fail() records the device error and closes runtime. */ }
                    finally { tickQueued.set(false); }
                });
            } catch (RejectedExecutionException e) { tickQueued.set(false); } // Ticks may coalesce; control commands may not.
        }
    }

    private void capture() {
        var snapshot = engine.snapshot();
        synchronized (gate) { latest = new State(revision, token, snapshot, closed, failure); }
    }
    private List<AudioRequest> detachRequests() {
        List<AudioRequest> obsolete = List.copyOf(requests.values()); requests.clear(); return obsolete;
    }
    private <T> CompletableFuture<T> enqueueLocked(Action<T> action) {
        if (closed) return rejected("Audio runtime closed");
        Command<T> command = new Command<>(action);
        try { worker.execute(command); }
        catch (RejectedExecutionException e) { command.result.completeExceptionally(e); }
        return command.result;
    }
    private static <T> CompletableFuture<T> rejected(String message) { return CompletableFuture.failedFuture(new IllegalStateException(message)); }
    private void failOnRejection(CompletableFuture<?> result) {
        result.whenComplete((value, error) -> { if (error instanceof RejectedExecutionException) fail(error); });
    }
    private void fail(Throwable cause) { failure = cause; close(); }

    @Override public void close() {
        List<AudioRequest> obsolete;
        List<Runnable> queued = new ArrayList<>();
        synchronized (gate) {
            if (closed) return;
            closed = true; revision++; token = null; scope = null;
            obsolete = detachRequests();
            timer.shutdownNow();
            worker.getQueue().drainTo(queued);
            worker.execute(() -> {
                try { engine.close(); }
                catch (RuntimeException e) { failure = e; }
                finally { capture(); }
            });
            worker.shutdown();
        }
        for (Runnable task : queued) if (task instanceof Command<?> command) command.discard();
        obsolete.forEach(AudioRequest::cancel);
    }

    /** No waiting on EDT or the audio worker itself. close() remains non-blocking. */
    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread() || Thread.currentThread() == audioThread) throw new IllegalStateException("Cleanup wait on a UI/audio worker");
        if (timeoutMs < 1 || timeoutMs > 60000) throw new IllegalArgumentException("Invalid cleanup timeout");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        if (!worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && timer.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }
}
