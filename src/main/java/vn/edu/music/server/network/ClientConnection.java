package vn.edu.music.server.network;

import vn.edu.music.common.protocol.Frame;
import vn.edu.music.common.protocol.FrameCodec;
import vn.edu.music.common.protocol.FrameType;
import vn.edu.music.common.protocol.JsonMessageCodec;
import vn.edu.music.common.protocol.ProtocolException;
import vn.edu.music.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/** One reader per connection; writeLock protects complete frames, including future broadcasts. */
public final class ClientConnection implements AutoCloseable {
    private final Socket socket;
    private final FrameCodec frames;
    private final JsonMessageCodec messages;
    private final Object writeLock = new Object();

    public ClientConnection(Socket socket, int maxFrameBytes, JsonMessageCodec messages) {
        this.socket = Objects.requireNonNull(socket);
        this.frames = new FrameCodec(maxFrameBytes);
        this.messages = Objects.requireNonNull(messages);
    }

    public ProtocolMessage readControl() throws IOException {
        Frame frame = frames.read(socket.getInputStream());
        if (frame == null) {
            return null;
        }
        if (frame.type() != FrameType.CONTROL_JSON) {
            throw new ProtocolException("Client must send control requests, not audio payloads");
        }
        return messages.decode(frame.payload());
    }

    public void writeControl(ProtocolMessage message) throws IOException {
        synchronized (writeLock) {
            frames.write(socket.getOutputStream(), new Frame(FrameType.CONTROL_JSON, messages.encode(message)));
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
