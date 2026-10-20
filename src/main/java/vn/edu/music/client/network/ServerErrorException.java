package vn.edu.music.client.network;

import vn.edu.music.common.protocol.Payloads;
import java.io.IOException;

public final class ServerErrorException extends IOException {
    private final Payloads.ErrorPayload error;

    public ServerErrorException(Payloads.ErrorPayload error) {
        super(error.code() + ": " + error.message());
        this.error = error;
    }

    public Payloads.ErrorPayload error() { return error; }
}
