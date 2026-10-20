package vn.edu.music.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.music.client.room.RoomStateStore;
import vn.edu.music.common.protocol.*;

import javax.swing.SwingUtilities;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

@Timeout(12)
class RoomControllerTest {
    @Test void slowServerNeverBlocksEdtAndAllViewCallbacksUseEdt() throws Exception {
        CountDownLatch requestRead = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch inRoom = new CountDownLatch(1);
        AtomicBoolean allOnEdt = new AtomicBoolean(true);
        try (var listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress()); var worker = Executors.newSingleThreadExecutor()) {
            var server = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(4000);
                    var frames = new FrameCodec(65536); var json = new JsonMessageCodec();
                    var request = json.decode(frames.read(socket.getInputStream()).payload()); requestRead.countDown();
                    assertTrue(allowResponse.await(3, TimeUnit.SECONDS));
                    frames.write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON,
                            json.encode(membership(MessageType.ROOM_CREATED, request.requestId(), state(1, 0, null)))));
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });
            try (var controller = new RoomController(new RoomController.View() {
                public void render(boolean online, RoomStateStore.View room) {
                    allOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
                    if (online) connected.countDown(); if (room.state() != null) inRoom.countDown();
                }
                public void status(String text) { allOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread()); }
            })) {
                SwingUtilities.invokeAndWait(() -> controller.connect("127.0.0.1", listener.getLocalPort()));
                assertTrue(connected.await(3, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> controller.create("Host"));
                assertTrue(requestRead.await(3, TimeUnit.SECONDS));
                CompletableFuture<Void> responsive = new CompletableFuture<>();
                SwingUtilities.invokeLater(() -> responsive.complete(null));
                responsive.get(1, TimeUnit.SECONDS); // Server has not answered yet; EDT still processes events.
                allowResponse.countDown(); assertTrue(inRoom.await(3, TimeUnit.SECONDS));
                assertTrue(allOnEdt.get());
            }
            server.get(4, TimeUnit.SECONDS);
        }
    }
}
