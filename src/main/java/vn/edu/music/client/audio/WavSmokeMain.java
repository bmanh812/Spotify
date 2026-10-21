package vn.edu.music.client.audio;

import javax.sound.sampled.*;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Opt-in, bounded local device diagnostic, separate from synchronized room playback. */
public final class WavSmokeMain {
    private WavSmokeMain() { }

    public record Result(long totalFrames, long durationMs, long actualPositionMs, long elapsedMs) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!args[0].equals("--tone") && !args[0].equals("--wav"))) {
            throw new IllegalArgumentException("Usage: WavSmokeMain --tone <generated.wav> | --wav <short-existing.wav>");
        }
        Path file = Path.of(args[1]).toAbsolutePath();
        if (args[0].equals("--tone")) writeTone(file);
        SourceDataLineSink sink = new SourceDataLineSink();
        StringBuilder report = new StringBuilder("# Local WAV device smoke\n\n")
                .append("Time UTC: ").append(Instant.now()).append("\n\n")
                .append("Java: ").append(System.getProperty("java.version")).append("\n\n")
                .append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append("\n\n")
                .append("Input: ").append(file).append("\n\n")
                .append("Expected format: PCM signed 16-bit LE, stereo, 44100 Hz\n\n")
                .append("Mixers: ");
        for (var mixer : AudioSystem.getMixerInfo()) report.append(mixer.getName()).append("; ");
        report.append("\n\n");
        Exception failure = null;
        try {
            Result result = play(file, sink);
            report.append("Status: DEVICE_API_PASS\n\n")
                    .append("Frames: ").append(result.totalFrames()).append("\n\n")
                    .append("Duration ms: ").append(result.durationMs()).append("\n\n")
                    .append("Processed position ms: ").append(result.actualPositionMs()).append("\n\n")
                    .append("Elapsed ms: ").append(result.elapsedMs()).append("\n\n");
        } catch (Exception e) {
            failure = e;
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            report.append("Status: FAILED\n\nError: ").append(cause.getClass().getSimpleName()).append(": ")
                    .append(cause.getMessage()).append("\n\n");
        }
        report.append("Device: ").append(sink.description()).append("\n\n")
                .append("Human listening confirmation: not recorded\n\n")
                .append("Scope: local WAV and device API only; synchronized TCP room playback is not exercised.\n");
        Path output = Path.of("target", "client-device-smoke.md").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString());
        System.out.println(report);
        System.out.println("Report: " + output);
        if (failure != null) throw failure;
    }

    /** Generates a quiet 440 Hz, 500 ms test WAV, never a Server library asset. */
    public static void writeTone(Path path) throws IOException {
        if (Files.exists(path)) throw new FileAlreadyExistsException(path.toString());
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        int frames = 22050;
        byte[] pcm = new byte[frames * 4];
        for (int frame = 0; frame < frames; frame++) {
            double fade = Math.min(1, Math.min(frame, frames - 1 - frame) / 441.0);
            short sample = (short) (1200 * fade * Math.sin(2 * Math.PI * 440 * frame / 44100));
            for (int channel = 0; channel < 2; channel++) {
                int offset = frame * 4 + channel * 2;
                pcm[offset] = (byte) sample; pcm[offset + 1] = (byte) (sample >> 8);
            }
        }
        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        // CREATE_NEW keeps this diagnostic from overwriting a user's existing track.
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
             var input = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
        }
    }

    public static Result play(Path path, AudioSink sink) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Run device smoke outside EDT");
        long began = System.nanoTime();
        long deadline = began + TimeUnit.SECONDS.toNanos(15);
        try (var source = LocalWavSource.open(path)) {
            if (source.track().durationMs() > 10000) throw new IllegalArgumentException("Device smoke accepts clips up to 10 seconds");
            AudioRuntime runtime = new AudioRuntime(sink, AudioRuntime.Settings.defaults());
            try {
                var scope = new AudioRuntime.StreamScope("local-smoke", source.track(), 1);
                runtime.updateScope(scope).get(3, TimeUnit.SECONDS);
                var token = runtime.reset(scope, 0).get(3, TimeUnit.SECONDS);
                boolean started = false;
                for (var chunk = source.next(); chunk != null; chunk = source.next()) {
                    while (!runtime.offer(token, chunk).get(3, TimeUnit.SECONDS)) waitForDevice(runtime, deadline);
                    if (!started) { runtime.start(token).get(3, TimeUnit.SECONDS); started = true; }
                }
                while (true) {
                    var state = runtime.snapshot().get(3, TimeUnit.SECONDS);
                    if (state.failure() != null) throw new IOException("Audio device failed", state.failure());
                    var snapshot = state.engine();
                    if (snapshot.actualPositionMs() >= source.track().durationMs()
                            && snapshot.appBufferFrames() == 0 && snapshot.lineBufferFrames() == 0) {
                        return new Result(source.track().totalFrames(), source.track().durationMs(), snapshot.actualPositionMs(),
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
                    }
                    waitForDevice(runtime, deadline);
                }
            } finally {
                runtime.close();
                if (!runtime.awaitTermination(3000)) throw new IOException("Audio cleanup timed out");
            }
        }
    }

    private static void waitForDevice(AudioRuntime runtime, long deadline) throws IOException, InterruptedException {
        if (runtime.latest().failure() != null) throw new IOException("Audio device failed", runtime.latest().failure());
        if (System.nanoTime() >= deadline) throw new IOException("Audio did not finish within the smoke deadline");
        TimeUnit.MILLISECONDS.sleep(10);
    }
}
