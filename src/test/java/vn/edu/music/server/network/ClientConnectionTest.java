package vn.edu.music.server.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.music.common.protocol.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionTest {
    @Test
    @Timeout(15)
    void concurrentWritersProduceWholeFramesNotInterleavedBytes() throws Exception {
        JsonMessageCodec messages = new JsonMessageCodec();
        FrameCodec frames = new FrameCodec(65536);
        try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
             ClientConnection connection = new ClientConnection(listener.accept(), 65536, messages);
             var executor = Executors.newFixedThreadPool(3)) {
            client.setSoTimeout(5000);
            List<ProtocolMessage> requests = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                requests.add(ProtocolMessage.request(MessageType.ROOM_STATE, "room-1",
                        Map.of("text", "a".repeat(2048), "index", i)));
            }
            Future<Set<String>> reader = executor.submit(() -> {
                Set<String> received = new HashSet<>();
                for (int i = 0; i < requests.size(); i++) {
                    Frame frame = frames.read(client.getInputStream());
                    assertNotNull(frame);
                    assertEquals(FrameType.CONTROL_JSON, frame.type());
                    ProtocolMessage decoded = messages.decode(frame.payload());
                    assertEquals("a".repeat(2048), decoded.payload().get("text"));
                    assertTrue(received.add(decoded.requestId()));
                }
                return received;
            });
            Future<?> first = executor.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    connection.writeControl(requests.get(i));
                }
                return null;
            });
            Future<?> second = executor.submit(() -> {
                for (int i = 50; i < 100; i++) {
                    connection.writeControl(requests.get(i));
                }
                return null;
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            Set<String> received = reader.get(5, TimeUnit.SECONDS);
            assertEquals(new HashSet<>(requests.stream().map(ProtocolMessage::requestId).toList()), received);
        }
    }
}
