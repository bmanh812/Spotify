package vn.edu.music.common.protocol;

/** Contract v1 review candidate: no existing message has been removed. */
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

    public boolean isStateChangingRequest() {
        return switch (this) {
            case CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, QUEUE_ADD, PLAY, PAUSE, SEEK, NEXT_TRACK -> true;
            default -> false;
        };
    }

    /** Audio responses use a binary frame, never a JSON control envelope. */
    public MessageType responseType() {
        return switch (this) {
            case CREATE_ROOM -> ROOM_CREATED;
            case JOIN_ROOM -> ROOM_STATE;
            case LEAVE_ROOM -> LEAVE_ACK;
            case QUEUE_ADD -> QUEUE_STATE;
            case PLAY, PAUSE, SEEK, NEXT_TRACK -> PLAYBACK_STATE;
            case AUDIO_CHUNK_REQUEST -> AUDIO_CHUNK;
            case PING -> PONG;
            case CLIENT_METRICS -> METRICS_ACK;
            default -> throw new IllegalStateException("Not a request: " + this);
        };
    }

    public boolean canBeServerPush() {
        return switch (this) {
            case ROOM_STATE, QUEUE_STATE, PLAYBACK_STATE, SYNC_STATE, ROOM_CLOSED -> true;
            default -> false;
        };
    }
}
