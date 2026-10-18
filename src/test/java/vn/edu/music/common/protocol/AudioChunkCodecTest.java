package vn.edu.music.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.music.common.model.TrackMetadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.common.protocol.ContractFixtures.*;

class AudioChunkCodecTest {
    private final AudioChunkCodec codec = new AudioChunkCodec();
    private AudioChunkMetadata metadata(long index, long start, int count, boolean last) {
        return new AudioChunkMetadata(MessageType.AUDIO_CHUNK, 1, ID, "r1", "t1", 1,
                index, start, count, last, count * 4);
    }

    @Test
    void full250msChunkRoundTripsThroughBinaryTcpFrameFormat() throws Exception {
        byte[] pcm = new byte[44100];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 127);
        AudioChunk original = new AudioChunk(metadata(0, 0, 11025, false), pcm);
        Frame frame = codec.encode(original);
        FrameCodec framing = new FrameCodec(65536);
        ByteArrayOutputStream wire = new ByteArrayOutputStream(); framing.write(wire, frame);
        assertEquals(frame.payload().length + 1, ByteBuffer.wrap(wire.toByteArray()).getInt());
        assertEquals(2, wire.toByteArray()[4]);
        AudioChunk decoded = codec.decode(framing.read(new ByteArrayInputStream(wire.toByteArray())));
        assertEquals(original.metadata(), decoded.metadata()); assertArrayEquals(pcm, decoded.pcm());
        AudioChunkCodec.validateAgainstTrack(decoded.metadata(), TRACK);
    }

    @Test
    void finalPartialChunkHasExactFrameCountWithoutPadding() throws Exception {
        TrackMetadata shortTrack = new TrackMetadata("t1", "Partial", 250, 44100, 2, 16, 4,
                11026, 44104, 11025, 2);
        AudioChunkMetadata last = metadata(1, 11025, 1, true);
        AudioChunkCodec.validateAgainstTrack(last, shortTrack);
        AudioChunk result = codec.decode(codec.encode(new AudioChunk(last, new byte[]{1, 2, 3, 4})));
        assertEquals(4, result.pcm().length); assertTrue(result.metadata().isLast());
    }

    @TestFactory
    Stream<DynamicTest> allBinaryMetadataFieldsAreRequired() {
        return Arrays.stream(AudioChunkMetadata.class.getRecordComponents()).map(field -> DynamicTest.dynamicTest(
                "Audio metadata missing " + field.getName(), () -> {
                    var node = ContractJson.mapper().valueToTree(metadata(0, 0, 1, true));
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(field.getName());
                    assertThrows(java.io.IOException.class, () -> codec.decode(raw(node.toString(), new byte[4])));
                }));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void rejectsInvalidStreamEpoch(long epoch) {
        AudioChunkMetadata invalid = new AudioChunkMetadata(MessageType.AUDIO_CHUNK, 1, ID, "r1", "t1",
                epoch, 0, 0, 1, true, 4);
        assertThrows(ProtocolException.class, () -> codec.encode(new AudioChunk(invalid, new byte[4])));
    }

    @Test
    void rejectsLengthAlignmentWrongTypeAndOversizedMetadata() throws Exception {
        assertThrows(ProtocolException.class, () -> codec.encode(new AudioChunk(metadata(0, 0, 1, true), new byte[3])));
        assertThrows(ProtocolException.class, () -> codec.decode(new Frame(FrameType.CONTROL_JSON, new byte[4])));
        assertThrows(ProtocolException.class, () -> codec.decode(new Frame(FrameType.AUDIO_CHUNK, new byte[]{0, 0, 1})));
        assertThrows(ProtocolException.class, () -> codec.decode(new Frame(FrameType.AUDIO_CHUNK, new byte[]{0, 20, 1})));
        assertThrows(ProtocolException.class, () -> codec.decode(raw(" ".repeat(2049), new byte[4])));
        assertThrows(ProtocolException.class, () -> codec.decode(raw("null", new byte[4])));
        assertThrows(ProtocolException.class, () -> codec.encode(new AudioChunk(metadata(0, 0, 15361, false), new byte[61444])));
    }

    @Test
    void contextualValidationRejectsWrongTrackStartCountAndFinalFlag() {
        assertThrows(ProtocolException.class, () -> AudioChunkCodec.validateAgainstTrack(metadata(4, 44100, 1, true), TRACK));
        assertThrows(ProtocolException.class, () -> AudioChunkCodec.validateAgainstTrack(metadata(1, 0, 11025, false), TRACK));
        assertThrows(ProtocolException.class, () -> AudioChunkCodec.validateAgainstTrack(metadata(0, 0, 1, false), TRACK));
        assertThrows(ProtocolException.class, () -> AudioChunkCodec.validateAgainstTrack(metadata(0, 0, 11025, true), TRACK));
    }

    @Test
    void maximumPcmChunkFitsV1FrameCeiling() throws Exception {
        AudioChunk chunk = new AudioChunk(metadata(0, 0, 15360, false), new byte[61440]);
        Frame frame = codec.encode(chunk);
        assertTrue(frame.payload().length + 1 <= ProtocolLimits.MAX_FRAME_BYTES);
        assertEquals(61440, codec.decode(frame).pcm().length);
    }

    @Test
    void documentedBinaryFixtureIsDecodedIndependentlyOfEncoder() throws Exception {
        JsonNode fixture = ContractJson.mapper().readTree(Files.readString(Path.of("docs/examples/audio-chunk-v1.json")));
        byte[] pcm = HexFormat.of().parseHex(fixture.get("pcmHex").asText());
        AudioChunk decoded = codec.decode(raw(fixture.get("metadata").toString(), pcm));
        assertEquals(ID, decoded.metadata().requestId());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.pcm());
        assertTrue(decoded.metadata().isLast());
        TrackMetadata track = new TrackMetadata("t1", "Fixture", 1, 44100, 2, 16, 4, 45, 180, 44, 2);
        AudioChunkCodec.validateAgainstTrack(decoded.metadata(), track);
    }

    private Frame raw(String metadataJson, byte[] pcm) {
        byte[] json = metadataJson.getBytes(StandardCharsets.UTF_8);
        return new Frame(FrameType.AUDIO_CHUNK, ByteBuffer.allocate(2 + json.length + pcm.length)
                .putShort((short) json.length).put(json).put(pcm).array());
    }
}
