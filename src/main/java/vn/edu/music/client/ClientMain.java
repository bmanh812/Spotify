package vn.edu.music.client;

import vn.edu.music.client.ui.RoomWindow;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/** TV1 Swing entry point. Scheduled audio integration awaits the TV2/TV3 handoff. */
public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Client UI requires a desktop. Run the Client tests for headless verification.");
            return;
        }
        SwingUtilities.invokeLater(() -> new RoomWindow().setVisible(true));
    }
}
