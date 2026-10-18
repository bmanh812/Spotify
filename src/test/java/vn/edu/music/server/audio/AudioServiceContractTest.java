package vn.edu.music.server.audio;

import org.junit.jupiter.api.Test;
import vn.edu.music.common.protocol.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AudioServiceContractTest {
    @Test
    void domainChunkDoesNotExposeMutablePcm() {
        byte[] source = {1, 2, 3, 4};
        AudioChunkData data = new AudioChunkData("r1", "t1", 1, 0, 0, 1, true, source);
        source[0] = 9; data.pcm()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, data.pcm());
    }

    @Test
    void serviceReturnsDomainDataNotWireEnvelope() throws Exception {
        assertEquals(AudioChunkData.class, AudioChunkService.class.getDeclaredMethods()[0].getReturnType());
        Set<String> fields = Arrays.stream(AudioChunkData.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        assertFalse(fields.contains("requestId"));
        assertFalse(fields.contains("protocolVersion"));
        assertFalse(fields.contains("type"));
        assertEquals(Set.of("roomId", "trackId", "streamEpoch", "chunkIndex", "startFrame", "frameCount", "isLast", "pcm"), fields);
    }

    @Test
    void protocolLayerCanEchoRequestIdWithoutGivingItToDomainService() throws Exception {
        ProtocolMessage incoming = ProtocolMessage.request(MessageType.AUDIO_CHUNK_REQUEST, "r1",
                Map.of("trackId", "t1", "streamEpoch", 1, "chunkIndex", 0));
        Payloads.ChunkRequest payload = (Payloads.ChunkRequest) ProtocolValidator.validateClientRequest(incoming);
        // Test fixture only, not a WAV reader or streaming implementation.
        AudioChunkService fixture = (room, actor, track, epoch, index) ->
                new AudioChunkData(room, track, epoch, index, 0, 1, true, new byte[]{1, 2, 3, 4});
        AudioChunkData data = fixture.read(incoming.roomId(), "c1", payload.trackId(), payload.streamEpoch(), payload.chunkIndex());
        AudioChunkMetadata metadata = new AudioChunkMetadata(MessageType.AUDIO_CHUNK, ProtocolLimits.VERSION,
                incoming.requestId(), data.roomId(), data.trackId(), data.streamEpoch(), data.chunkIndex(),
                data.startFrame(), data.frameCount(), data.isLast(), data.pcm().length);
        AudioChunkCodec codec = new AudioChunkCodec();
        AudioChunk decoded = codec.decode(codec.encode(new AudioChunk(metadata, data.pcm())));
        assertEquals(incoming.requestId(), decoded.metadata().requestId());
        assertArrayEquals(data.pcm(), decoded.pcm());
    }
}
