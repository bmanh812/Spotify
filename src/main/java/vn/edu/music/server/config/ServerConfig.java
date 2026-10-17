package vn.edu.music.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ServerConfig(String host, int port, int workerThreads,
                           int queueCapacity, int socketReadTimeoutMs, int maxFrameBytes) {
    public ServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("server.host is required");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 0 and 65535");
        }
        if (workerThreads < 1 || queueCapacity < 1 || socketReadTimeoutMs < 0 || maxFrameBytes < 1) {
            throw new IllegalArgumentException("Invalid server limits or timeout");
        }
    }

    public static ServerConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return new ServerConfig(required(properties, "server.host"), integer(properties, "server.port"),
                integer(properties, "server.workerThreads"), integer(properties, "server.queueCapacity"),
                integer(properties, "server.socketReadTimeoutMs"), integer(properties, "protocol.maxFrameBytes"));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + key);
        }
        return value.strip();
    }

    private static int integer(Properties properties, String key) {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Configuration must be an integer: " + key, error);
        }
    }
}
