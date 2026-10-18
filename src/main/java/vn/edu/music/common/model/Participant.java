package vn.edu.music.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Server-issued clientId; nickname is only a display name, not an authorization credential. */
public record Participant(@JsonProperty(required = true) String clientId,
                          @JsonProperty(required = true) String nickname) {
}
