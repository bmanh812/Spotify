package vn.edu.music.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QueueItem(@JsonProperty(required = true) String queueItemId,
                        @JsonProperty(required = true) String trackId,
                        @JsonProperty(required = true) String addedByClientId) {
}
