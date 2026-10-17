package vn.edu.music.server.network;

import vn.edu.music.common.protocol.JsonMessageCodec;
import vn.edu.music.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.Socket;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ClientHandler implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());
    private final Socket socket;
    private final int maxFrameBytes;
    private final JsonMessageCodec messages;
    private final MessageDispatcher dispatcher;
    private final Consumer<Socket> onClosed;

    ClientHandler(Socket socket, int maxFrameBytes, JsonMessageCodec messages,
                  MessageDispatcher dispatcher, Consumer<Socket> onClosed) {
        this.socket = socket;
        this.maxFrameBytes = maxFrameBytes;
        this.messages = messages;
        this.dispatcher = dispatcher;
        this.onClosed = onClosed;
    }

    @Override
    public void run() {
        try (ClientConnection connection = new ClientConnection(socket, maxFrameBytes, messages)) {
            ProtocolMessage request;
            while ((request = connection.readControl()) != null) {
                connection.writeControl(dispatcher.dispatch(request));
            }
        } catch (IOException | RuntimeException error) {
            LOG.log(Level.FINE, "Connection closed: " + socket.getRemoteSocketAddress(), error);
        } finally {
            onClosed.accept(socket);
        }
    }
}
