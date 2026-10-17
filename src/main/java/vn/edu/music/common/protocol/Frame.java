package vn.edu.music.common.protocol;

import java.util.Objects;

public record Frame(FrameType type, byte[] payload) {
    public Frame {
        Objects.requireNonNull(type, "type is required");
        payload = Objects.requireNonNull(payload, "payload is required").clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
