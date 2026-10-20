package vn.edu.music.client.ui;

import vn.edu.music.client.room.RoomStateStore;
import vn.edu.music.common.protocol.MessageType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class RoomWindow extends JFrame implements RoomController.View {
    private final JTextField host = new JTextField("127.0.0.1", 12);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(9090, 1, 65535, 1));
    private final JTextField nickname = new JTextField("TV1", 10);
    private final JTextField code = new JTextField(6);
    private final JButton connect = new JButton("Kết nối");
    private final JButton disconnect = new JButton("Ngắt kết nối");
    private final JButton create = new JButton("Tạo phòng");
    private final JButton join = new JButton("Vào phòng");
    private final JButton leave = new JButton("Rời phòng");
    private final JButton add = new JButton("Thêm vào hàng chờ");
    private final JButton play = new JButton("Phát");
    private final JButton pause = new JButton("Tạm dừng");
    private final JButton next = new JButton("Bài tiếp");
    private final JButton seek = new JButton("Chuyển đến giây");
    private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(0L, 0L, 86400L, 1L));
    private final JLabel summary = new JLabel("Chưa kết nối");
    private final JLabel status = new JLabel("Sẵn sàng");
    private final DefaultListModel<String> members = new DefaultListModel<>();
    private final DefaultListModel<String> tracks = new DefaultListModel<>();
    private final DefaultListModel<String> queue = new DefaultListModel<>();
    private final JList<String> trackList = new JList<>(tracks);
    private java.util.List<String> trackIds = java.util.List.of();
    private final RoomController controller;

    public RoomWindow() {
        super("Phòng nghe nhạc — TV1 Client");
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create Swing window on EDT");
        controller = new RoomController(this);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new GridLayout(0, 1, 4, 4));
        top.add(row(new JLabel("Server"), host, port, connect, disconnect));
        top.add(row(new JLabel("Nickname"), nickname, new JLabel("Mã phòng"), code, create, join, leave));
        top.add(summary); root.add(top, BorderLayout.NORTH);
        JPanel lists = new JPanel(new GridLayout(1, 3, 10, 0));
        lists.add(listPanel("Thành viên", new JList<>(members)));
        lists.add(listPanel("Thư viện", trackList));
        lists.add(listPanel("Hàng chờ", new JList<>(queue)));
        root.add(lists, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new GridLayout(0, 1, 6, 6));
        bottom.add(row(add, play, pause, next, seconds, seek));
        bottom.add(new JLabel("Âm thanh đồng bộ: chờ tích hợp Server và module TV3."));
        bottom.add(status); root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root); setSize(1050, 500); setLocationByPlatform(true);
        connect.addActionListener(e -> controller.connect(host.getText().strip(), (Integer) port.getValue()));
        disconnect.addActionListener(e -> controller.disconnect());
        create.addActionListener(e -> controller.create(nickname.getText()));
        join.addActionListener(e -> controller.join(nickname.getText(), code.getText()));
        leave.addActionListener(e -> controller.leave());
        add.addActionListener(e -> {
            int index = trackList.getSelectedIndex();
            if (index >= 0 && index < trackIds.size()) controller.addTrack(trackIds.get(index));
        });
        play.addActionListener(e -> controller.playback(MessageType.PLAY, 0));
        pause.addActionListener(e -> controller.playback(MessageType.PAUSE, 0));
        next.addActionListener(e -> controller.playback(MessageType.NEXT_TRACK, 0));
        seek.addActionListener(e -> controller.playback(MessageType.SEEK, ((Number) seconds.getValue()).longValue() * 1000));
        addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { controller.close(); } });
        render(false, new RoomStateStore().view());
    }

    private static JPanel row(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (var component : components) panel.add(component);
        return panel;
    }

    private static JPanel listPanel(String title, JList<String> list) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(new JScrollPane(list)); return panel;
    }

    @Override public void render(boolean connected, RoomStateStore.View room) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Render outside EDT");
        boolean active = connected && room.state() != null;
        boolean ready = connected && !room.lifecyclePending() && !room.uncertain();
        connect.setEnabled(!connected); disconnect.setEnabled(connected);
        create.setEnabled(ready && !active); join.setEnabled(ready && !active); leave.setEnabled(ready && active);
        host.setEnabled(!connected); port.setEnabled(!connected);
        add.setEnabled(ready && active);
        for (JButton button : new JButton[]{play, pause, next, seek}) button.setEnabled(ready && active && room.isHost());
        members.clear(); tracks.clear(); queue.clear();
        trackIds = room.tracks().stream().map(t -> t.trackId()).toList();
        room.tracks().forEach(t -> tracks.addElement(t.title() + " (" + t.durationMs() / 1000 + " s)"));
        if (active) {
            var state = room.state();
            state.participants().forEach(p -> members.addElement(p.nickname() + (p.clientId().equals(state.hostClientId()) ? " [Host]" : "")));
            state.queue().forEach(q -> queue.addElement(room.tracks().stream().filter(t -> t.trackId().equals(q.trackId()))
                    .findFirst().map(t -> t.title()).orElse(q.trackId())));
            summary.setText("Phòng " + state.roomCode() + " | " + (room.isHost() ? "Host" : "Member")
                    + " | " + state.playbackState() + " | version=" + state.roomVersion() + " epoch=" + state.streamEpoch());
        } else { summary.setText(connected ? "Đã kết nối, chưa ở trong phòng" : "Chưa kết nối"); }
        if (room.uncertain()) status("Kết quả thao tác chưa rõ; hãy ngắt kết nối trước khi tiếp tục.");
    }

    @Override public void status(String message) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Status outside EDT");
        status.setText(message);
    }
}
