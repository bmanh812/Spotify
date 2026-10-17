package vn.edu.music.common.protocol;

/** Proposed catalog only. Payload contracts are NOT frozen. Owner: member 1, reviewed by 2 and 3. */
public enum MessageType {
    CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, QUEUE_ADD,
    PLAY, PAUSE, SEEK, NEXT_TRACK, AUDIO_CHUNK_REQUEST, PING, CLIENT_METRICS,
    ROOM_CREATED, ROOM_STATE, LEAVE_ACK, PLAYBACK_STATE, QUEUE_STATE,
    AUDIO_CHUNK, PONG, METRICS_ACK, SYNC_STATE, ROOM_CLOSED, ERROR;

    public boolean isClientRequest() {
        return switch (this) {
            case CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, QUEUE_ADD, PLAY, PAUSE,
                    SEEK, NEXT_TRACK, AUDIO_CHUNK_REQUEST, PING, CLIENT_METRICS -> true;
            default -> false;
        };
    }
}
