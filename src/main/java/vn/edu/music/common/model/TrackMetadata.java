package vn.edu.music.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Canonical PCM metadata. WAV parsing and validation are intentionally not implemented yet. */
public record TrackMetadata(
        @JsonProperty(required = true) String trackId,
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) long durationMs,
        @JsonProperty(required = true) int sampleRate,
        @JsonProperty(required = true) int channels,
        @JsonProperty(required = true) int sampleSizeBits,
        @JsonProperty(required = true) int frameSizeBytes,
        @JsonProperty(required = true) long totalFrames,
        @JsonProperty(required = true) long totalPcmBytes,
        @JsonProperty(required = true) int chunkFrames,
        @JsonProperty(required = true) long chunkCount) {
}
