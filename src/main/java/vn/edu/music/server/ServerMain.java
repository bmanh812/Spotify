package vn.edu.music.server;

import vn.edu.music.server.config.ServerConfig;
import vn.edu.music.server.network.BootstrapDispatcher;
import vn.edu.music.server.network.MusicServer;

import java.nio.file.Path;
import java.util.logging.Logger;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length == 0 ? "config/application.properties" : args[0]);
        ServerConfig config = ServerConfig.load(file);
        try (MusicServer server = new MusicServer(config, new BootstrapDispatcher())) {
            server.start();
            Thread hook = new Thread(server::close, "music-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                Logger.getLogger(ServerMain.class.getName()).info(
                        "Music Room skeleton listening on " + config.host() + ":" + server.port()
                                + " -- room/audio handlers are not implemented");
                server.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shutdownInProgress) {
                    // JVM shutdown already started; the registered hook owns cleanup.
                }
            }
        }
    }
}
