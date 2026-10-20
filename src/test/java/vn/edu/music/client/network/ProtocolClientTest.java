package vn.edu.music.client.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.music.common.protocol.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

@Timeout(15)
class ProtocolClientTest {
    interface Script { void run(Peer peer) throws Exception; }
    static final class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final FrameCodec frames = new FrameCodec(65536);
        final JsonMessageCodec json = new JsonMessageCodec();
        final Future<?> task;
        volatile Socket socket;
        Peer(Script script) throws IOException {
            task = executor.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    socket = accepted; accepted.setSoTimeout(5000);
                    script.run(this);
                    release.await(5, TimeUnit.SECONDS);
                }
                return null;
            });
        }
        int port() { return listener.getLocalPort(); }
        ProtocolMessage read() throws IOException { return json.decode(Objects.requireNonNull(frames.read(socket.getInputStream())).payload()); }
        void send(ProtocolMessage message) throws IOException { frames.write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON, json.encode(message))); }
        void pong(ProtocolMessage ping) throws IOException {
            long t1 = ((Payloads.Ping) PayloadCodec.decode(ping)).clientSendTimeMs();
            send(PayloadCodec.message(MessageType.PONG, ping.requestId(), null, new Payloads.Pong(t1, TIME, TIME)));
        }
        @Override public void close() throws Exception {
            release.countDown(); listener.close();
            try { task.get(6, TimeUnit.SECONDS); }
            finally { if (socket != null) socket.close(); executor.shutdownNow(); }
        }
    }

    private static ProtocolMessage ping() throws ProtocolException {
        return PayloadCodec.message(MessageType.PING, id(), null, new Payloads.Ping(TIME));
    }
    private static ProtocolClient connect(Peer peer, ProtocolClient.Listener listener) throws IOException {
        return ProtocolClient.connect("127.0.0.1", peer.port(), new ClientSettings(1000, 3000, 32, 32, 64), listener);
    }

    @Test void correlatesOutOfOrderRepliesAndSeparatesPush() throws Exception {
        BlockingQueue<ProtocolClient.Incoming> events = new LinkedBlockingQueue<>();
        try (Peer peer = new Peer(p -> {
            var first = p.read(); var second = p.read();
            p.send(PayloadCodec.message(MessageType.ROOM_CLOSED, id(), "other-room",
                    new Payloads.RoomClosed(2, Payloads.CloseReason.HOST_LEFT, TIME)));
            p.pong(second); p.pong(first);
        }); ProtocolClient client = connect(peer, events::add)) {
            var one = ping(); var two = ping();
            var f1 = client.request(one); var f2 = client.request(two);
            assertEquals(two.requestId(), f2.get(4, TimeUnit.SECONDS).request().requestId());
            var r1 = f1.get(4, TimeUnit.SECONDS);
            assertEquals(one.requestId(), r1.request().requestId());
            assertTrue(r1.receivedNanos() >= r1.sentNanos());
            assertNull(events.poll(2, TimeUnit.SECONDS).request());
            assertEquals(0, client.pendingCount());
        }
    }

    @Test void validatesBinaryCorrelationAndRetainsPcm() throws Exception {
        var request = PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, id(), "r1", new Payloads.ChunkRequest("t1", 1, 0));
        try (Peer peer = new Peer(p -> {
            var r = p.read(); var original = chunk(0, 1); var m = original.metadata();
            var metadata = new AudioChunkMetadata(m.type(), 1, r.requestId(), "r1", "t1", 1, 0, 0, 11025, false, 44100);
            p.frames.write(p.socket.getOutputStream(), new AudioChunkCodec().encode(new AudioChunk(metadata, original.pcm())));
        }); ProtocolClient client = connect(peer, e -> { })) {
            assertArrayEquals(chunk(0, 1).pcm(), client.request(request).get(4, TimeUnit.SECONDS).audio().pcm());
        }
    }

    @Test void rejectsResponseWithWrongRoomAndCleansPending() throws Exception {
        var request = PayloadCodec.message(MessageType.PLAY, id(), "r1", new Payloads.PlaybackCommand(1));
        try (Peer peer = new Peer(p -> {
            var r = p.read(); p.send(ProtocolMessage.error(new ProtocolMessage(r.type(), 1, r.requestId(), "r2", r.payload()),
                    ErrorCode.NOT_HOST, "Wrong room"));
        }); ProtocolClient client = connect(peer, e -> { })) {
            assertInstanceOf(ProtocolException.class, assertThrows(ExecutionException.class,
                    () -> client.request(request).get(4, TimeUnit.SECONDS)).getCause());
            assertTrue(client.isClosed()); assertEquals(0, client.pendingCount());
        }
    }

    @Test void timeoutDoesNotTreatLateMembershipResponseAsPushOrRetry() throws Exception {
        CountDownLatch allowLate = new CountDownLatch(1);
        BlockingQueue<ProtocolClient.Incoming> events = new LinkedBlockingQueue<>();
        var request = PayloadCodec.message(MessageType.CREATE_ROOM, id(), null, new Payloads.CreateRoom("Host"));
        try (Peer peer = new Peer(p -> {
            var r = p.read(); assertTrue(allowLate.await(3, TimeUnit.SECONDS));
            p.send(membership(MessageType.ROOM_CREATED, r.requestId(), state(1, 0, null)));
            p.pong(p.read());
        }); ProtocolClient client = ProtocolClient.connect("127.0.0.1", peer.port(), new ClientSettings(1000, 200, 4, 4, 8), events::add)) {
            assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class,
                    () -> client.request(request).get(2, TimeUnit.SECONDS)).getCause());
            allowLate.countDown();
            assertThrows(ExecutionException.class, () -> client.request(request).get(2, TimeUnit.SECONDS));
            client.request(ping()).get(2, TimeUnit.SECONDS);
            assertEquals(MessageType.PONG, events.poll(2, TimeUnit.SECONDS).control().type());
            assertTrue(events.isEmpty()); assertEquals(0, client.pendingCount());
        }
    }

    @Test void serverBusinessErrorIsTypedAndConnectionRemainsUsable() throws Exception {
        try (Peer peer = new Peer(p -> {
            var r = p.read(); p.send(ProtocolMessage.error(r, ErrorCode.NOT_IMPLEMENTED, "Bootstrap")); p.pong(p.read());
        }); ProtocolClient client = connect(peer, e -> { })) {
            var error = assertThrows(ExecutionException.class, () -> client.request(ping()).get(4, TimeUnit.SECONDS));
            assertEquals(ErrorCode.NOT_IMPLEMENTED, ((ServerErrorException) error.getCause()).error().code());
            assertNotNull(client.request(ping()).get(4, TimeUnit.SECONDS)); assertFalse(client.isClosed());
        }
    }

    @Test void malformedFrameDisconnectsAndNotifiesListener() throws Exception {
        CompletableFuture<Throwable> disconnected = new CompletableFuture<>();
        try (Peer peer = new Peer(p -> { p.read(); p.socket.getOutputStream().write(new byte[]{0, 0, 0, 0}); });
             ProtocolClient client = connect(peer, new ProtocolClient.Listener() {
                 public void onIncoming(ProtocolClient.Incoming e) { fail("Malformed frame delivered"); }
                 public void onDisconnected(Throwable cause) { disconnected.complete(cause); }
             })) {
            assertThrows(ExecutionException.class, () -> client.request(ping()).get(4, TimeUnit.SECONDS));
            assertInstanceOf(ProtocolException.class, disconnected.get(4, TimeUnit.SECONDS));
        }
    }

    @Test void finalReplyBeforeEofCompletesFuture() throws Exception {
        try (Peer peer = new Peer(p -> { p.pong(p.read()); p.socket.shutdownOutput(); });
             ProtocolClient client = connect(peer, e -> { })) {
            assertEquals(MessageType.PONG, client.request(ping()).get(4, TimeUnit.SECONDS).control().type());
        }
    }

    @Test void boundedPendingCancellationAndCloseReleaseRequests() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        try (Peer peer = new Peer(p -> { p.read(); read.countDown(); });
             ProtocolClient client = ProtocolClient.connect("127.0.0.1", peer.port(), new ClientSettings(1000, 4000, 1, 1, 4), e -> { })) {
            var first = client.request(ping()); assertTrue(read.await(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> client.request(ping()).get(2, TimeUnit.SECONDS));
            assertTrue(first.cancel(false)); assertEquals(0, client.pendingCount());
            var next = client.request(ping()); client.close(); client.close();
            assertThrows(ExecutionException.class, () -> next.get(2, TimeUnit.SECONDS));
            assertEquals(0, client.pendingCount());
            assertTrue(client.awaitTermination(2000));
        }
    }

    @Test void concurrentRequestsProduceWholeFrames() throws Exception {
        try (Peer peer = new Peer(p -> { for (int i = 0; i < 24; i++) p.pong(p.read()); });
             ProtocolClient client = connect(peer, e -> { }); var callers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) tasks.add(callers.submit(() -> client.request(ping()).get(4, TimeUnit.SECONDS)));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(0, client.pendingCount());
        }
    }

    @Test void unrelatedRequestIdCannotCompletePendingRequest() throws Exception {
        BlockingQueue<ProtocolClient.Incoming> events = new LinkedBlockingQueue<>();
        try (Peer peer = new Peer(p -> {
            var request = p.read();
            p.pong(new ProtocolMessage(request.type(), 1, id(), null, request.payload()));
            p.pong(request);
        }); ProtocolClient client = connect(peer, events::add)) {
            var request = ping();
            assertEquals(request.requestId(), client.request(request).get(4, TimeUnit.SECONDS).control().requestId());
            assertEquals(1, events.size());
        }
    }

    @Test void invalidServerDirectionAndPongEchoCloseConnection() throws Exception {
        for (boolean wrongDirection : new boolean[]{true, false}) {
            try (Peer peer = new Peer(p -> {
                var request = p.read();
                if (wrongDirection) p.send(request);
                else p.send(PayloadCodec.message(MessageType.PONG, request.requestId(), null, new Payloads.Pong(1, TIME, TIME)));
            }); ProtocolClient client = connect(peer, e -> { })) {
                assertInstanceOf(ProtocolException.class, assertThrows(ExecutionException.class,
                        () -> client.request(ping()).get(4, TimeUnit.SECONDS)).getCause());
                assertTrue(client.isClosed()); assertTrue(client.awaitTermination(2000));
            }
        }
    }

    @Test void mismatchedAudioIndexNeverReachesListener() throws Exception {
        var request = PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, id(), "r1", new Payloads.ChunkRequest("t1", 1, 0));
        try (Peer peer = new Peer(p -> {
            var r = p.read(); var c = chunk(1, 1); var m = c.metadata();
            var bad = new AudioChunk(new AudioChunkMetadata(m.type(), 1, r.requestId(), "r1", "t1", 1, 1,
                    m.startFrame(), m.frameCount(), m.isLast(), m.pcmByteLength()), c.pcm());
            p.frames.write(p.socket.getOutputStream(), new AudioChunkCodec().encode(bad));
        }); ProtocolClient client = connect(peer, e -> fail("Invalid chunk delivered"))) {
            assertInstanceOf(ProtocolException.class, assertThrows(ExecutionException.class,
                    () -> client.request(request).get(4, TimeUnit.SECONDS)).getCause());
            assertEquals(0, client.pendingCount());
        }
    }

    @Test void audioWindowLeavesCapacityForControlRequests() throws Exception {
        CountDownLatch readAudio = new CountDownLatch(1);
        try (Peer peer = new Peer(p -> {
            assertEquals(MessageType.AUDIO_CHUNK_REQUEST, p.read().type()); readAudio.countDown();
            p.pong(p.read());
        }); ProtocolClient client = ProtocolClient.connect("127.0.0.1", peer.port(), new ClientSettings(1000, 3000, 4, 4, 8, 1), e -> { })) {
            var audio = PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, id(), "r1", new Payloads.ChunkRequest("t1", 1, 0));
            var first = client.request(audio); assertTrue(readAudio.await(2, TimeUnit.SECONDS));
            var second = PayloadCodec.message(MessageType.AUDIO_CHUNK_REQUEST, id(), "r1", new Payloads.ChunkRequest("t1", 1, 1));
            assertThrows(ExecutionException.class, () -> client.request(second).get(2, TimeUnit.SECONDS));
            assertNotNull(client.request(ping()).get(2, TimeUnit.SECONDS));
            first.cancel(false);
        }
    }
}
