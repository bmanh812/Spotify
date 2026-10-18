package vn.edu.music.common.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** JSON metadata inside a type 0x02 binary frame, NOT a control envelope. */
public record AudioChunkMetadata(
        @JsonProperty(required = true) MessageType type,
        @JsonProperty(required = true) int protocolVersion,
        @JsonProperty(required = true) String requestId,
        @JsonProperty(required = true) String roomId,
        @JsonProperty(required = true) String trackId,
        @JsonProperty(required = true) long streamEpoch,
        @JsonProperty(required = true) long chunkIndex,
        @JsonProperty(required = true) long startFrame,
        @JsonProperty(required = true) int frameCount,
        @JsonProperty(required = true) boolean isLast,
        @JsonProperty(required = true) int pcmByteLength) { }
