package vn.edu.music.client.network;

/** Client resource policy, not additional wire invariants or Server configuration. */
public record ClientSettings(int connectTimeoutMs, int requestTimeoutMs, int maxPending,
                             int outboundCapacity, int eventCapacity, int maxAudioPending) {
    public ClientSettings(int connectTimeoutMs, int requestTimeoutMs, int maxPending, int outboundCapacity, int eventCapacity) {
        this(connectTimeoutMs, requestTimeoutMs, maxPending, outboundCapacity, eventCapacity, Math.min(4, maxPending));
    }

    public ClientSettings {
        if (connectTimeoutMs < 1 || requestTimeoutMs < 1 || maxPending < 1
                || outboundCapacity < 1 || eventCapacity < 1 || maxAudioPending < 1 || maxAudioPending > maxPending) {
            throw new IllegalArgumentException("Client limits must be positive");
        }
    }

    public static ClientSettings defaults() {
        return new ClientSettings(3000, 5000, 64, 64, 128, 4);
    }
}
