package vn.edu.music.client.room;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.music.client.audio.*;
import vn.edu.music.client.network.*;
import vn.edu.music.common.model.TrackMetadata;
import vn.edu.music.common.protocol.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

@Timeout(15)
class MusicClientTest {
    interface Script { void run(Wire wire) throws Exception; }
    /** Fixture is Client-owned test code, not an alternative production Server. */
    static final class Fixture implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final Future<?> script;
        volatile Socket accepted;
        Fixture(Script action) throws IOException {
            script = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    accepted = socket; socket.setSoTimeout(4000); action.run(new Wire(socket));
                    release.await(5, TimeUnit.SECONDS);
                }
                return null;
            });
        }
        MusicClient connect() throws IOException {
            return MusicClient.connect("127.0.0.1", listener.getLocalPort(), new ClientSettings(1000, 3000, 16, 16, 32));
        }
        public void close() throws Exception {
            release.countDown(); listener.close();
            try { script.get(6, TimeUnit.SECONDS); }
            finally { if (accepted != null) accepted.close(); worker.shutdownNow(); }
        }
    }
    record Wire(Socket socket) {
        ProtocolMessage read() throws IOException {
            var frame = new FrameCodec(65536).read(socket.getInputStream());
            assertNotNull(frame); return new JsonMessageCodec().decode(frame.payload());
        }
        void send(ProtocolMessage message) throws IOException {
            new FrameCodec(65536).write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON, new JsonMessageCodec().encode(message)));
        }
        void create() throws IOException {
            var r = read(); assertEquals(MessageType.CREATE_ROOM, r.type());
            send(membership(MessageType.ROOM_CREATED, r.requestId(), state(1, 0, null)));
        }
        void audio(ProtocolMessage request, int index) throws IOException {
            var c = chunk(index, 1); var m = c.metadata();
            var response = new AudioChunk(new AudioChunkMetadata(m.type(), 1, request.requestId(), "r1", "t1", 1,
                    index, m.startFrame(), m.frameCount(), m.isLast(), m.pcmByteLength()), c.pcm());
            new FrameCodec(65536).write(socket.getOutputStream(), new AudioChunkCodec().encode(response));
        }
    }

    @Test void createStatePushCommandAndLeaveUseOneStatePipeline() throws Exception {
        try (Fixture fixture = new Fixture(wire -> {
            wire.create();
            var add = wire.read(); assertEquals(MessageType.QUEUE_ADD, add.type());
            wire.send(PayloadCodec.message(MessageType.QUEUE_STATE, add.requestId(), "r1", new Payloads.StateUpdate(state(2, 1, "t1"), TIME)));
            wire.send(update(state(3, 1, "t1")));
            wire.send(update(state(2, 1, "t1"))); // Stale push cannot roll state back.
            var ping = wire.read();
            long t1 = ((Payloads.Ping) PayloadCodec.decode(ping)).clientSendTimeMs();
            wire.send(PayloadCodec.message(MessageType.PONG, ping.requestId(), null, new Payloads.Pong(t1, TIME, TIME)));
            var leave = wire.read(); assertEquals(MessageType.LEAVE_ROOM, leave.type());
            wire.send(PayloadCodec.message(MessageType.LEAVE_ACK, leave.requestId(), "r1", new Payloads.LeaveAck("c1", 4, true)));
            wire.send(update(state(5, 2, "t1")));
            var barrier = wire.read();
            long sent = ((Payloads.Ping) PayloadCodec.decode(barrier)).clientSendTimeMs();
            wire.send(PayloadCodec.message(MessageType.PONG, barrier.requestId(), null, new Payloads.Pong(sent, TIME, TIME)));
        }); MusicClient client = fixture.connect()) {
            client.create("Host").get(4, TimeUnit.SECONDS); assertTrue(client.view().isHost());
            client.addTrack("t1").get(4, TimeUnit.SECONDS);
            client.ping().get(4, TimeUnit.SECONDS); assertEquals(3, client.view().state().roomVersion());
            client.leave().get(4, TimeUnit.SECONDS); assertNull(client.view().state());
            client.ping().get(4, TimeUnit.SECONDS); assertNull(client.view().state());
        }
    }

    @Test void serverBootstrapErrorsRemainVisibleAndLifecycleCanBeRetriedAsNewAction() throws Exception {
        try (Fixture fixture = new Fixture(wire -> {
            var request = wire.read(); wire.send(ProtocolMessage.error(request, ErrorCode.NOT_IMPLEMENTED, "Bootstrap only")); wire.create();
        }); MusicClient client = fixture.connect()) {
            var error = assertThrows(ExecutionException.class, () -> client.create("Host").get(4, TimeUnit.SECONDS));
            assertInstanceOf(ServerErrorException.class, error.getCause());
            assertFalse(client.view().lifecyclePending()); assertFalse(client.view().uncertain());
            client.create("Host").get(4, TimeUnit.SECONDS); assertNotNull(client.view().state());
        }
    }

    @Test void pcmTravelsThroughTcpReceiverAndEngineWithMatchingHash() throws Exception {
        class Sink implements AudioSink {
            final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            long frames;
            public void open(TrackMetadata track) { frames = 0; pcm.reset(); }
            public int availableBytes() { return 44100; }
            public long bufferedFrames() { return 0; }
            public long processedFrames() { return frames; }
            public int write(byte[] bytes, int offset, int length) {
                int count = Math.min(101 * 4, length); pcm.write(bytes, offset, count); frames += count / 4; return count;
            }
            public void start() { }
            public void stop() { }
            public void flush() { }
            public void close() { }
        }
        Sink sink = new Sink();
        try (Fixture fixture = new Fixture(wire -> {
            wire.create(); wire.send(update(state(2, 1, "t1")));
            for (int index = 0; index < 4; index++) {
                var request = wire.read(); assertEquals(MessageType.AUDIO_CHUNK_REQUEST, request.type()); wire.audio(request, index);
            }
        }); MusicClient client = fixture.connect(); PlaybackEngine engine = new PlaybackEngine(sink, 500)) {
            client.create("Host").get(4, TimeUnit.SECONDS);
            // A latch observes the full state on the same ordered event pipeline; no arbitrary sleeps.
            CompletableFuture<Void> selected = new CompletableFuture<>();
            client.observe(view -> { if (view.state() != null && view.state().currentTrackId() != null) selected.complete(null); });
            selected.get(4, TimeUnit.SECONDS);
            long generation = engine.reset("r1", TRACK, 1, 0);
            AudioReceiver receiver = new AudioReceiver(client::view, engine);
            ByteArrayOutputStream source = new ByteArrayOutputStream();
            for (int index = 0; index < 4; index++) {
                var incoming = client.chunk(index).get(4, TimeUnit.SECONDS);
                assertTrue(receiver.accept(incoming, generation));
                while (engine.snapshot().appContiguousFrames() > 0) engine.pump(1024, generation);
                source.write(chunk(index, 1).pcm());
            }
            var hash = MessageDigest.getInstance("SHA-256");
            assertArrayEquals(hash.digest(source.toByteArray()), hash.digest(sink.pcm.toByteArray()));
            assertEquals(1000, engine.snapshot().actualPositionMs());
            long nextGeneration = engine.reset("r1", TRACK, 1, 0);
            assertNotEquals(nextGeneration, generation);
            var stale = new ProtocolClient.Incoming(null, chunk(0, 1),
                    PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, id(), "r1", new Payloads.ChunkRequest("t1", 1, 0)), TIME, 1, 1);
            assertFalse(receiver.accept(stale, generation));
        }
    }
}
