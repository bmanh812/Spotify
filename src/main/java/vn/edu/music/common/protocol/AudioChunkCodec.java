package vn.edu.music.common.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.edu.music.common.model.TrackMetadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

import static vn.edu.music.common.protocol.ProtocolLimits.*;
import static vn.edu.music.common.protocol.ProtocolValidator.*;

/** Binary serialization only. Does not read WAV files, send sockets or schedule playback. */
public final class AudioChunkCodec {
    private final ObjectMapper mapper = ContractJson.mapper();

    public Frame encode(AudioChunk chunk) throws IOException {
        byte[] pcm = chunk.pcm();
        validateMetadata(chunk.metadata(), pcm.length);
        byte[] metadata = mapper.writeValueAsBytes(chunk.metadata());
        require(metadata.length <= MAX_METADATA_BYTES, "Audio metadata exceeds 2048 bytes");
        ByteBuffer bytes = ByteBuffer.allocate(2 + metadata.length + pcm.length);
        bytes.putShort((short) metadata.length).put(metadata).put(pcm);
        return new Frame(FrameType.AUDIO_CHUNK, bytes.array());
    }

    public AudioChunk decode(Frame frame) throws IOException {
        require(frame.type() == FrameType.AUDIO_CHUNK, "Expected binary AUDIO_CHUNK frame");
        byte[] payload = frame.payload();
        require(payload.length + 1L <= MAX_FRAME_BYTES && payload.length >= 3, "Invalid audio body size");
        ByteBuffer bytes = ByteBuffer.wrap(payload);
        int metadataLength = Short.toUnsignedInt(bytes.getShort());
        require(metadataLength > 0 && metadataLength <= MAX_METADATA_BYTES && metadataLength <= bytes.remaining(),
                "Invalid metadataLength");
        byte[] json = new byte[metadataLength];
        bytes.get(json);
        AudioChunkMetadata metadata = mapper.readValue(json, AudioChunkMetadata.class);
        validateMetadata(metadata, bytes.remaining());
        byte[] pcm = new byte[bytes.remaining()];
        bytes.get(pcm);
        return new AudioChunk(metadata, pcm);
    }

    public static void validateMetadata(AudioChunkMetadata metadata, int actualPcmBytes) throws ProtocolException {
        require(metadata != null, "metadata is required");
        require(metadata.type() == MessageType.AUDIO_CHUNK, "Metadata type must be AUDIO_CHUNK");
        if (metadata.protocolVersion() != VERSION) throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Unsupported audio version");
        try {
            require(metadata.requestId() != null
                    && UUID.fromString(metadata.requestId()).toString().equalsIgnoreCase(metadata.requestId()), "Invalid requestId");
        } catch (IllegalArgumentException error) {
            throw new ProtocolException("Invalid requestId");
        }
        id(metadata.roomId(), "roomId"); id(metadata.trackId(), "trackId");
        positive(metadata.streamEpoch(), "streamEpoch"); nonnegative(metadata.chunkIndex(), "chunkIndex");
        nonnegative(metadata.startFrame(), "startFrame");
        require(metadata.frameCount() > 0 && metadata.frameCount() <= MAX_PCM_BYTES / FRAME_SIZE_BYTES,
                "Invalid frameCount");
        require(metadata.startFrame() <= Long.MAX_VALUE - metadata.frameCount(), "Frame offset overflow");
        require(metadata.pcmByteLength() == metadata.frameCount() * FRAME_SIZE_BYTES
                && actualPcmBytes == metadata.pcmByteLength(), "PCM length/frame alignment mismatch");
    }

    /** Contextual checks with the immutable catalog; stale room/epoch checks remain the caller's job. */
    public static void validateAgainstTrack(AudioChunkMetadata metadata, TrackMetadata track) throws ProtocolException {
        require(metadata != null, "metadata is required");
        validateMetadata(metadata, metadata.pcmByteLength()); validateTrack(track);
        require(metadata.trackId().equals(track.trackId()), "trackId mismatch");
        require(metadata.chunkIndex() < track.chunkCount(), "chunkIndex outside track");
        long start = metadata.chunkIndex() * track.chunkFrames(); // index bound prevents overflow
        int count = (int) Math.min(track.chunkFrames(), track.totalFrames() - start);
        require(metadata.startFrame() == start && metadata.frameCount() == count,
                "Chunk index/start/count mismatch");
        require(metadata.isLast() == (start + count == track.totalFrames()), "isLast mismatch");
    }
}
