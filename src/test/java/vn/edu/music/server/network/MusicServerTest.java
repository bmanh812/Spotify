package vn.edu.music.server.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.music.common.protocol.*;
import vn.edu.music.server.config.ServerConfig;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class MusicServerTest {
    private MusicServer server;
    private final FrameCodec frames = new FrameCodec(65536);
    private final JsonMessageCodec messages = new JsonMessageCodec();

    @BeforeEach
    void start() throws Exception {
        server = new MusicServer(new ServerConfig("127.0.0.1", 0, 4, 8, 3000, 65536),
                new BootstrapDispatcher());
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void realTcpRequestGetsCorrelatedHonestStubResponse() throws Exception {
        try (Socket socket = connect()) {
            ProtocolMessage request = request();
            ProtocolMessage response = exchange(socket, request);
            assertEquals(MessageType.ERROR, response.type());
            assertEquals(request.requestId(), response.requestId());
            assertEquals("NOT_IMPLEMENTED", response.payload().get("code"));
        }
    }

    @Test
    void invalidVersionAndDirectionDoNotBreakNextRequest() throws Exception {
        try (Socket socket = connect()) {
            ProtocolMessage base = request();
            assertEquals("UNSUPPORTED_VERSION", exchange(socket, new ProtocolMessage(base.type(), 99,
                    base.requestId(), null, base.payload())).payload().get("code"));
            assertEquals("INVALID_REQUEST", exchange(socket,
                    ProtocolMessage.request(MessageType.ROOM_STATE, null, Map.of())).payload().get("code"));
            assertEquals("NOT_IMPLEMENTED", exchange(socket, request()).payload().get("code"));
        }
    }

    @Test
    void multipleClientsAreHandledIndependently() throws Exception {
        try (var executor = Executors.newFixedThreadPool(3)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                results.add(executor.submit(() -> {
                    try (Socket socket = connect()) {
                        ProtocolMessage request = request();
                        assertEquals(request.requestId(), exchange(socket, request).requestId());
                        return request.requestId();
                    }
                }));
            }
            List<String> ids = new ArrayList<>();
            for (Future<String> result : results) {
                ids.add(result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(3, ids.stream().distinct().count());
        }
    }

    @Test
    void malformedFrameOnlyClosesOffendingClient() throws Exception {
        try (Socket bad = connect()) {
            bad.getOutputStream().write(new byte[]{0, 0, 0, 0});
            bad.getOutputStream().flush();
            assertEquals(-1, bad.getInputStream().read());
        }
        try (Socket healthy = connect()) {
            assertEquals("NOT_IMPLEMENTED", exchange(healthy, request()).payload().get("code"));
        }
    }

    @Test
    void jsonNullIsRejectedAsMalformedNotAnEnvelope() throws Exception {
        try (Socket socket = connect()) {
            frames.write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON, new byte[]{'n', 'u', 'l', 'l'}));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void closeIsIdempotentAndReleasesIdleConnectionAndAcceptThread() throws Exception {
        try (Socket socket = connect()) {
            exchange(socket, request()); // Ensure the connection is accepted before shutdown.
            server.close();
            server.close();
            assertEquals(-1, socket.getInputStream().read());
            server.awaitTermination();
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    @Test
    void cannotStartTwice() {
        assertThrows(IllegalStateException.class, server::start);
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 3000);
            socket.setSoTimeout(3000);
            return socket;
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    private ProtocolMessage request() {
        return ProtocolMessage.request(MessageType.CREATE_ROOM, null, Map.of("nickname", "Minh"));
    }

    private ProtocolMessage exchange(Socket socket, ProtocolMessage request) throws Exception {
        frames.write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON, messages.encode(request)));
        Frame response = frames.read(socket.getInputStream());
        assertNotNull(response);
        return messages.decode(response.payload());
    }
}
