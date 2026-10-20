package vn.edu.music.client.ui;

import org.junit.jupiter.api.Test;
import vn.edu.music.client.room.RoomStateStore;
import vn.edu.music.common.model.Participant;
import vn.edu.music.common.model.QueueItem;
import vn.edu.music.common.model.RoomState;
import vn.edu.music.common.model.PlaybackState;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static vn.edu.music.client.ClientFixtures.*;

class RoomWindowTest {
    @Test void rendersHostAndMemberControlsWithoutOpeningAVisibleWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop renderer is unavailable");
        SwingUtilities.invokeAndWait(() -> {
            RoomWindow window = new RoomWindow();
            try {
                var state = new RoomState("r1", "ABC123", "c1", List.of(new Participant("c1", "Host"), new Participant("c2", "Member")),
                        "t1", PlaybackState.PAUSED, 0, TIME, 3, 1, List.of(new QueueItem("q1", "t1", "c2")));
                window.render(true, new RoomStateStore.View("c1", state, List.of(TRACK), false, false));
                assertTrue(button(window, "Phát").isEnabled());
                window.status("Fixture UI TV1 — chưa kết nối Server thật");
                window.addNotify(); window.validate();
                var content = window.getContentPane();
                var image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { content.printAll(graphics); } finally { graphics.dispose(); }
                var pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
                assertTrue(java.util.Arrays.stream(pixels).distinct().limit(20).count() >= 10,
                        "UI preview must contain rendered controls");
                try {
                    Files.createDirectories(Path.of("target"));
                    ImageIO.write(image, "png", Path.of("target/client-ui-preview.png").toFile());
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                window.render(true, new RoomStateStore.View("c2", state, List.of(TRACK), false, false));
                assertFalse(button(window, "Phát").isEnabled());
                assertTrue(button(window, "Thêm vào hàng chờ").isEnabled());
                window.render(true, new RoomStateStore.View("c2", state, List.of(TRACK), false, true));
                assertFalse(button(window, "Thêm vào hàng chờ").isEnabled());
                assertTrue(button(window, "Ngắt kết nối").isEnabled());
            } finally { window.dispose(); }
        });
    }

    private static JButton button(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && button.getText().equals(text)) return button;
            if (component instanceof Container child) {
                try { return button(child, text); } catch (java.util.NoSuchElementException ignored) { }
            }
        }
        throw new java.util.NoSuchElementException(text);
    }
}
