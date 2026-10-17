package vn.edu.music.server.room;

/** Server-issued clientId; nickname is only a display name, not an authorization credential. */
public record Participant(String clientId, String nickname) {
}
