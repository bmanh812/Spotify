package vn.edu.music.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir
    Path temporary;

    @Test
    void loadsCommittedConfiguration() throws Exception {
        ServerConfig config = ServerConfig.load(Path.of("config/application.properties"));
        assertEquals("127.0.0.1", config.host());
        assertEquals(9090, config.port());
        assertTrue(config.workerThreads() > 0);
        assertTrue(config.socketReadTimeoutMs() > 0);
        assertTrue(config.maxFrameBytes() > 0);
    }

    @ParameterizedTest
    @CsvSource({"-1, 1, 1, 1, 1", "65536, 1, 1, 1, 1", "0, 0, 1, 1, 1",
            "0, 1, 0, 1, 1", "0, 1, 1, -1, 1", "0, 1, 1, 1, 0"})
    void rejectsInvalidLimits(int port, int threads, int queue, int timeout, int maxFrame) {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConfig("127.0.0.1", port, threads, queue, timeout, maxFrame));
    }

    @Test
    void allowsEphemeralPortAndRejectsBlankHost() {
        assertEquals(0, new ServerConfig("127.0.0.1", 0, 1, 1, 0, 1).port());
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig(" ", 0, 1, 1, 0, 1));
    }

    @Test
    void reportsMissingFileMissingKeyAndMalformedInteger() throws Exception {
        assertThrows(IOException.class, () -> ServerConfig.load(temporary.resolve("missing.properties")));
        Path file = temporary.resolve("test.properties");
        Files.writeString(file, "server.host=127.0.0.1\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.load(file)).getMessage().contains("server.port"));
        Files.writeString(file, "server.host=127.0.0.1\nserver.port=oops\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.load(file)).getMessage().contains("integer"));
    }
}
