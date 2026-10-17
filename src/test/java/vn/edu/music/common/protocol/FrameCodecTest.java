package vn.edu.music.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FrameCodecTest {
    private final FrameCodec codec = new FrameCodec(1024);

    @ParameterizedTest
    @EnumSource(FrameType.class)
    void roundTripBothFrameTypes(FrameType type) throws Exception {
        byte[] payload = {0, 1, -1, 42};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(output, new Frame(type, payload));
        Frame decoded = codec.read(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(type, decoded.type());
        assertArrayEquals(payload, decoded.payload());
        assertEquals(5, ByteBuffer.wrap(output.toByteArray()).getInt());
    }

    @Test
    void handlesFragmentedReadsAndMultipleFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(output, new Frame(FrameType.CONTROL_JSON, new byte[]{10, 20}));
        codec.write(output, new Frame(FrameType.AUDIO_CHUNK, new byte[0]));
        try (FilterInputStream input = new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        }) {
            assertArrayEquals(new byte[]{10, 20}, codec.read(input).payload());
            assertEquals(FrameType.AUDIO_CHUNK, codec.read(input).type());
            assertNull(codec.read(input));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1025})
    void rejectsInvalidLengthBeforeReadingBody(int length) {
        byte[] header = ByteBuffer.allocate(4).putInt(length).array();
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(header)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsTruncatedHeader(int bytes) {
        assertThrows(EOFException.class, () -> codec.read(new ByteArrayInputStream(new byte[bytes])));
    }

    @Test
    void rejectsTruncatedPayloadAndMissingType() {
        assertThrows(EOFException.class,
                () -> codec.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 3, 1, 9})));
        assertThrows(EOFException.class,
                () -> codec.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 1})));
    }

    @Test
    void rejectsUnknownFrameType() {
        assertThrows(ProtocolException.class,
                () -> codec.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 1, 99})));
    }

    @Test
    void oversizedWriteProducesNoPartialHeader() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(ProtocolException.class,
                () -> codec.write(output, new Frame(FrameType.CONTROL_JSON, new byte[1024])));
        assertEquals(0, output.size());
    }

    @Test
    void frameDefensivelyCopiesPayload() {
        byte[] source = {1, 2};
        Frame frame = new Frame(FrameType.AUDIO_CHUNK, source);
        Arrays.fill(source, (byte) 0);
        Arrays.fill(frame.payload(), (byte) 0);
        assertArrayEquals(new byte[]{1, 2}, frame.payload());
    }
}
