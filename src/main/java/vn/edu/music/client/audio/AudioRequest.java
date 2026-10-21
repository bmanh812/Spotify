package vn.edu.music.client.audio;

import vn.edu.music.client.network.ProtocolClient;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** A local read handle; cancellation never claims to cancel Server-side IO. */
public final class AudioRequest {
    private final String requestId;
    private final long chunkIndex;
    private final AudioRuntime.Token token;
    private final AtomicReference<CompletableFuture<ProtocolClient.Incoming>> upstream = new AtomicReference<>();
    private final CompletableFuture<ProtocolClient.Incoming> response = new CompletableFuture<>() {
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(false);
            var source = upstream.get();
            if (cancelled && source != null) source.cancel(false);
            return cancelled;
        }
    };

    AudioRequest(String requestId, long chunkIndex, AudioRuntime.Token token) {
        this.requestId = Objects.requireNonNull(requestId);
        this.chunkIndex = chunkIndex;
        this.token = Objects.requireNonNull(token);
    }

    /** Attach exactly once. A reset may already have cancelled this handle before transport admission. */
    public void attach(CompletableFuture<ProtocolClient.Incoming> source) {
        if (!upstream.compareAndSet(null, Objects.requireNonNull(source))) throw new IllegalStateException("Request already attached");
        if (response.isCancelled()) source.cancel(false);
        source.whenComplete((incoming, error) -> {
            if (error == null) response.complete(incoming);
            else response.completeExceptionally(error);
        });
    }

    public String requestId() { return requestId; }
    public long chunkIndex() { return chunkIndex; }
    public AudioRuntime.Token token() { return token; }
    public CompletableFuture<ProtocolClient.Incoming> response() { return response; }
    public boolean cancel() { return response.cancel(false); }
}
