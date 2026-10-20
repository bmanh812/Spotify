package vn.edu.music.client.ui;

import vn.edu.music.client.network.ClientSettings;
import vn.edu.music.client.room.MusicClient;
import vn.edu.music.client.room.RoomStateStore;
import vn.edu.music.common.protocol.MessageType;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.function.Function;

/** UI orchestration only. RoomStateStore is the authoritative local state-apply pipeline. */
public final class RoomController implements AutoCloseable {
    public interface View {
        void render(boolean connected, RoomStateStore.View room);
        void status(String message);
    }

    private final View view;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> new Thread(r, "client-ui-commands"));
    private volatile MusicClient client;
    private volatile boolean disposed;

    public RoomController(View view) { this.view = java.util.Objects.requireNonNull(view); }

    public void connect(String host, int port) {
        submit(() -> {
            if (client != null && client.isConnected()) { status("Đã kết nối"); return; }
            if (client != null) client.close();
            status("Đang kết nối...");
            try {
                MusicClient opened = MusicClient.connect(host, port, ClientSettings.defaults());
                if (disposed) { opened.close(); return; }
                client = opened;
                opened.observe(snapshot -> {
                    if (client != opened || disposed) return;
                    onEdt(() -> view.render(opened.isConnected(), snapshot));
                    if (!opened.isConnected()) status("Đã ngắt kết nối");
                });
                status(opened.isConnected() ? "Đã kết nối; có thể tạo hoặc vào phòng" : "Server đã ngắt kết nối");
            } catch (Exception e) { status(errorMessage(e)); }
        });
    }

    public void disconnect() {
        submit(() -> {
            MusicClient previous = client; client = null;
            if (previous != null) previous.close();
            onEdt(() -> view.render(false, new RoomStateStore().view()));
            status("Đã ngắt kết nối");
        });
    }

    public void create(String nickname) { action(c -> c.create(nickname)); }
    public void join(String nickname, String code) { action(c -> c.join(nickname, code)); }
    public void leave() { action(MusicClient::leave); }
    public void addTrack(String track) { action(c -> c.addTrack(track)); }
    public void playback(MessageType type, long positionMs) { action(c -> c.playback(type, positionMs)); }

    private void action(Function<MusicClient, CompletableFuture<?>> command) {
        submit(() -> {
            MusicClient current = client;
            if (current == null || !current.isConnected()) { status("Hãy kết nối Server trước"); return; }
            try {
                command.apply(current).whenComplete((result, error) -> {
                    if (disposed || client != current) return;
                    status(error == null ? "Thao tác hoàn tất" : errorMessage(error));
                });
            } catch (RuntimeException e) { status(errorMessage(e)); }
        });
    }

    private void submit(Runnable action) {
        if (disposed) return;
        try { worker.execute(action); }
        catch (RejectedExecutionException e) { status("Đang có quá nhiều thao tác; vui lòng chờ"); }
    }

    private void status(String text) { onEdt(() -> view.status(text)); }
    private void onEdt(Runnable action) { SwingUtilities.invokeLater(() -> { if (!disposed) action.run(); }); }
    private static String errorMessage(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        worker.getQueue().clear();
        worker.execute(() -> { if (client != null) { client.close(); client = null; } });
        worker.shutdown();
    }
}
