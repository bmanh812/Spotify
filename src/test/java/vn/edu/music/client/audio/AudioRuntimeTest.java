package vn.edu.music.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.music.common.model.TrackMetadata;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

@Timeout(12)
class AudioRuntimeTest {
    static final class Sink implements AudioSink {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        volatile CountDownLatch writeEntered;
        volatile CountDownLatch releaseWrite;
        volatile CountDownLatch firstPump;
        volatile boolean failOpen;
        volatile boolean failClose;
        volatile boolean deviceClosed;
        int starts;
        boolean running;
        long frames;
        long queued;
        void record() { threads.add(Thread.currentThread().getName()); }
        public void open(TrackMetadata track) throws LineUnavailableException {
            record(); if (failOpen) throw new LineUnavailableException("No test device");
            frames = 0; queued = 0; running = false; deviceClosed = false; written.reset();
        }
        public int availableBytes() { record(); return 44100 - (int) queued * 4; }
        public long bufferedFrames() { record(); return queued; }
        public long processedFrames() { record(); return frames; }
        public int write(byte[] bytes, int offset, int length) {
            record();
            CountDownLatch entered = writeEntered; CountDownLatch release = releaseWrite;
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test write not released"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                writeEntered = null;
            }
            int count = Math.min(length, 400);
            written.write(bytes, offset, count);
            if (running) frames += count / 4; else queued += count / 4;
            if (firstPump != null) firstPump.countDown();
            return count;
        }
        public void start() { record(); starts++; running = true; frames += queued; queued = 0; }
        public void stop() { record(); running = false; }
        public void flush() { record(); queued = 0; }
        public void close() {
            record(); deviceClosed = true; running = false; queued = 0;
            if (failClose) throw new IllegalStateException("Device close failed");
        }
    }

    private static AudioRuntime runtime(Sink sink, int capacity, int tickMs) {
        return new AudioRuntime(sink, new AudioRuntime.Settings(6000, capacity, tickMs, 2048));
    }
    private static AudioRuntime.StreamScope scope(long epoch) { return new AudioRuntime.StreamScope("r1", TRACK, epoch); }
    private static AudioRuntime.Token prepare(AudioRuntime runtime) throws Exception {
        runtime.updateScope(scope(1)).get(2, TimeUnit.SECONDS);
        return runtime.reset(scope(1), 0).get(2, TimeUnit.SECONDS);
    }

    @Test void allDeviceCallsRunOnOneWorkerAndSnapshotsDoNotCountQueuedBytesAsPosition() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 8, 0);
        try {
            var token = prepare(runtime);
            assertTrue(runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS));
            assertTrue(runtime.pump(token).get(2, TimeUnit.SECONDS) > 0);
            var primed = runtime.snapshot().get(2, TimeUnit.SECONDS).engine();
            assertEquals(0, primed.actualPositionMs()); assertTrue(primed.lineBufferFrames() > 0);
            assertTrue(runtime.start(token).get(2, TimeUnit.SECONDS));
            assertTrue(runtime.snapshot().get(2, TimeUnit.SECONDS).engine().actualPositionMs() > 0);
            assertTrue(runtime.pause(token).get(2, TimeUnit.SECONDS));
        } finally { runtime.close(); assertTrue(runtime.awaitTermination(2000)); }
        assertTrue(sink.deviceClosed); assertEquals(Set.of("client-audio"), sink.threads);
    }

    @Test void sameStreamUpdatePreservesTokenAndScopeChangeInvalidatesItImmediately() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 8, 0);
        try {
            var token = prepare(runtime); runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS);
            runtime.updateScope(scope(1)).get(2, TimeUnit.SECONDS);
            assertTrue(runtime.isCurrent(token)); assertEquals(11025, runtime.snapshot().get(2, TimeUnit.SECONDS).engine().appBufferFrames());
            var request = runtime.reserveRequest(token, 1, id());
            var changed = runtime.updateScope(scope(2));
            assertFalse(runtime.isCurrent(token)); assertTrue(request.response().isCancelled());
            changed.get(2, TimeUnit.SECONDS);
            assertFalse(runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS));
            assertFalse(runtime.start(token).get(2, TimeUnit.SECONDS));
            assertNull(runtime.snapshot().get(2, TimeUnit.SECONDS).engine().trackId());
            var next = runtime.reset(scope(2), 0).get(2, TimeUnit.SECONDS);
            assertTrue(runtime.offer(next, chunk(0, 2)).get(2, TimeUnit.SECONDS));
        } finally { runtime.close(); assertTrue(runtime.awaitTermination(2000)); }
    }

    @Test void resetCancelsPreAttachHandleAndNeverExecutesQueuedOldStart() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 8, 0);
        try {
            var token = prepare(runtime); runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS);
            sink.writeEntered = new CountDownLatch(1); sink.releaseWrite = new CountDownLatch(1);
            var entered = sink.writeEntered;
            var pumping = runtime.pump(token); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var oldStart = runtime.start(token);
            var handle = runtime.reserveRequest(token, 1, id());
            var reset = runtime.reset(scope(1), 12345);
            assertTrue(handle.response().isCancelled());
            var source = new CompletableFuture<vn.edu.music.client.network.ProtocolClient.Incoming>();
            handle.attach(source); assertTrue(source.isCancelled());
            sink.releaseWrite.countDown(); pumping.get(2, TimeUnit.SECONDS);
            var next = reset.get(2, TimeUnit.SECONDS);
            assertFalse(oldStart.get(2, TimeUnit.SECONDS)); assertEquals(0, sink.starts);
            assertNotEquals(token.engineGeneration(), next.engineGeneration()); assertEquals(0, runtime.pendingRequests());
        } finally {
            if (sink.releaseWrite != null) sink.releaseWrite.countDown();
            runtime.close(); assertTrue(runtime.awaitTermination(2000));
        }
    }

    @Test void duplicateRequestReservationIsBoundedAndCompletingOneReleasesItsIndex() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 8, 0);
        try {
            var token = prepare(runtime); var first = runtime.reserveRequest(token, 0, id());
            assertThrows(IllegalStateException.class, () -> runtime.reserveRequest(token, 0, id()));
            var upstream = new CompletableFuture<vn.edu.music.client.network.ProtocolClient.Incoming>(); first.attach(upstream);
            upstream.completeExceptionally(new TimeoutException()); assertEquals(0, runtime.pendingRequests());
            var replacement = runtime.reserveRequest(token, 0, id()); replacement.cancel(); assertEquals(0, runtime.pendingRequests());
        } finally { runtime.close(); assertTrue(runtime.awaitTermination(2000)); }
    }

    @Test void saturatedControlQueueFailsClosedAndSettlesQueuedFutures() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 1, 0);
        try {
            var token = prepare(runtime); runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS);
            sink.writeEntered = new CountDownLatch(1); sink.releaseWrite = new CountDownLatch(1);
            var entered = sink.writeEntered;
            var pumping = runtime.pump(token); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = runtime.snapshot();
            var rejected = runtime.start(token);
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                    () -> rejected.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(queued.isCompletedExceptionally()); assertFalse(runtime.isCurrent(token));
            sink.releaseWrite.countDown(); pumping.get(2, TimeUnit.SECONDS);
            assertTrue(runtime.awaitTermination(2000));
            assertTrue(runtime.latest().closed()); assertNotNull(runtime.latest().failure()); assertTrue(sink.deviceClosed);
        } finally {
            if (sink.releaseWrite != null) sink.releaseWrite.countDown();
            runtime.close(); assertTrue(runtime.awaitTermination(2000));
        }
    }

    @Test void automaticPumpWorksWithoutUsingPolicyTickAndCloseIsNonBlockingOnEdt() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 8, 10);
        try {
            var token = prepare(runtime); sink.firstPump = new CountDownLatch(1);
            runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS);
            assertTrue(sink.firstPump.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                runtime.close(); runtime.close();
                assertThrows(IllegalStateException.class, () -> runtime.awaitTermination(2000));
            });
            assertTrue(runtime.awaitTermination(2000)); assertTrue(sink.deviceClosed);
        } finally { runtime.close(); assertTrue(runtime.awaitTermination(2000)); }
    }

    @Test void openingDeviceFailureIsReportedAndThreadsStop() throws Exception {
        Sink sink = new Sink(); sink.failOpen = true;
        AudioRuntime runtime = runtime(sink, 8, 10);
        runtime.updateScope(scope(1)).get(2, TimeUnit.SECONDS);
        assertInstanceOf(LineUnavailableException.class, assertThrows(ExecutionException.class,
                () -> runtime.reset(scope(1), 0).get(2, TimeUnit.SECONDS)).getCause());
        assertTrue(runtime.awaitTermination(2000));
        assertNotNull(runtime.latest().failure()); assertTrue(sink.deviceClosed);
    }

    @Test void deviceCloseFailureStillPublishesTerminalSnapshotAndStopsWorkers() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 8, 10);
        prepare(runtime); sink.failClose = true;
        runtime.close(); assertTrue(runtime.awaitTermination(2000));
        assertTrue(runtime.latest().closed()); assertNotNull(runtime.latest().failure());
        assertNull(runtime.latest().engine().trackId()); assertEquals(0, runtime.latest().engine().appBufferFrames());
    }

    @Test void delayedPumpDoesNotAccumulateTimerTasksOrCrowdOutCommands() throws Exception {
        Sink sink = new Sink(); AudioRuntime runtime = runtime(sink, 2, 1);
        try {
            var token = prepare(runtime);
            sink.writeEntered = new CountDownLatch(1); sink.releaseWrite = new CountDownLatch(1);
            var entered = sink.writeEntered;
            runtime.offer(token, chunk(0, 1)).get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> interval = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> interval.complete(null));
            interval.get(2, TimeUnit.SECONDS);
            var snapshot = runtime.snapshot(); var pause = runtime.pause(token);
            assertFalse(snapshot.isCompletedExceptionally()); assertFalse(pause.isCompletedExceptionally());
            sink.releaseWrite.countDown(); snapshot.get(2, TimeUnit.SECONDS); assertTrue(pause.get(2, TimeUnit.SECONDS));
        } finally {
            if (sink.releaseWrite != null) sink.releaseWrite.countDown();
            runtime.close(); assertTrue(runtime.awaitTermination(2000));
        }
    }
}
