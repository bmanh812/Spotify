package vn.edu.music.common.protocol;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** V1 framing. Each connection must serialize complete writes with its own write lock. */
public final class FrameCodec {
    private final int maxFrameBytes;

    public FrameCodec(int maxFrameBytes) {
        if (maxFrameBytes < 1 || maxFrameBytes > ProtocolLimits.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("maxFrameBytes must be within the v1 ceiling");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /** Null means clean EOF before the next frame. Truncated frames throw EOFException. */
    public Frame read(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) {
            return null;
        }
        byte[] rest = input.readNBytes(3);
        if (rest.length != 3) {
            throw new EOFException("Truncated frame length");
        }
        int length = (first << 24) | ((rest[0] & 0xff) << 16)
                | ((rest[1] & 0xff) << 8) | (rest[2] & 0xff);
        checkLength(length);
        int type = input.read();
        if (type == -1) {
            throw new EOFException("Missing frame type");
        }
        FrameType frameType = FrameType.fromWire(type);
        byte[] payload = input.readNBytes(length - 1);
        if (payload.length != length - 1) {
            throw new EOFException("Truncated frame payload");
        }
        return new Frame(frameType, payload);
    }

    public void write(OutputStream output, Frame frame) throws IOException {
        byte[] payload = frame.payload();
        long length = 1L + payload.length;
        if (length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE, "Frame exceeds configured maximum");
        }
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt((int) length);
        data.writeByte(frame.type().wireValue());
        data.write(payload);
        data.flush();
    }

    private void checkLength(int length) throws ProtocolException {
        if (length < 1 || length > maxFrameBytes) {
            throw new ProtocolException(length > maxFrameBytes ? ErrorCode.FRAME_TOO_LARGE
                    : ErrorCode.INVALID_REQUEST, "Invalid frame length: " + length);
        }
    }
}
