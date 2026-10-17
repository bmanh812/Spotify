package vn.edu.music.server.network;

import vn.edu.music.common.protocol.JsonMessageCodec;
import vn.edu.music.server.config.ServerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runnable TCP bootstrap only; room business logic is deliberately not wired yet. */
public final class MusicServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MusicServer.class.getName());
    private final ServerConfig config;
    private final MessageDispatcher dispatcher;
    private final JsonMessageCodec messages = new JsonMessageCodec();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final ThreadPoolExecutor workers;
    private volatile boolean running;
    private boolean closed;
    private ServerSocket listener;
    private Thread acceptThread;

    public MusicServer(ServerConfig config, MessageDispatcher dispatcher) {
        this.config = Objects.requireNonNull(config);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(), 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.queueCapacity()),
                task -> new Thread(task, "music-client-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void start() throws IOException {
        synchronized (lifecycle) {
            if (closed || listener != null) {
                throw new IllegalStateException("Server cannot be started again");
            }
            ServerSocket opened = new ServerSocket();
            try {
                opened.bind(new InetSocketAddress(config.host(), config.port()));
            } catch (IOException | RuntimeException error) {
                opened.close();
                throw error;
            }
            listener = opened;
            running = true;
            acceptThread = new Thread(() -> acceptLoop(opened), "music-accept");
            acceptThread.start();
        }
    }

    public int port() {
        synchronized (lifecycle) {
            if (listener == null) {
                throw new IllegalStateException("Server has not started");
            }
            return listener.getLocalPort();
        }
    }

    public void awaitTermination() throws InterruptedException {
        Thread thread;
        synchronized (lifecycle) {
            thread = acceptThread;
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.join();
        }
    }

    private void acceptLoop(ServerSocket activeListener) {
        while (running) {
            Socket accepted = null;
            try {
                accepted = activeListener.accept();
                accepted.setSoTimeout(config.socketReadTimeoutMs());
                accepted.setTcpNoDelay(true);
                synchronized (lifecycle) {
                    if (!running) {
                        closeQuietly(accepted);
                        return;
                    }
                    sockets.add(accepted);
                    workers.execute(new ClientHandler(accepted, config.maxFrameBytes(), messages,
                            dispatcher, sockets::remove));
                }
            } catch (IOException | RuntimeException error) {
                if (accepted != null) {
                    sockets.remove(accepted);
                    closeQuietly(accepted);
                }
                if (running) {
                    LOG.log(Level.WARNING, "Connection could not be accepted or scheduled", error);
                }
            }
        }
    }

    @Override
    public void close() {
        Thread thread;
        List<Socket> active;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException error) {
                    LOG.log(Level.FINE, "Listener close failed", error);
                }
            }
            active = List.copyOf(sockets);
            thread = acceptThread;
            workers.shutdownNow();
        }
        active.forEach(MusicServer::closeQuietly);
        sockets.clear();
        try {
            if (thread != null && thread != Thread.currentThread()) {
                thread.join(TimeUnit.SECONDS.toMillis(3));
            }
            if (!workers.awaitTermination(3, TimeUnit.SECONDS)) {
                LOG.warning("Some dispatcher tasks did not stop within the shutdown timeout");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Other connections must still be closed.
        }
    }
}
