package vn.edu.music.common.protocol;

/** Draft codes. NOT_IMPLEMENTED is used by the bootstrap dispatcher until handlers are added. */
public enum ErrorCode {
    INVALID_REQUEST, UNSUPPORTED_VERSION, FRAME_TOO_LARGE,
    ROOM_NOT_FOUND, ROOM_FULL, ROOM_CLOSED, NOT_HOST,
    TRACK_NOT_FOUND, INVALID_SEEK, STALE_STATE, STALE_STREAM, EMPTY_QUEUE,
    RATE_LIMITED, SERVER_BUSY, INTERNAL_ERROR, NOT_IMPLEMENTED
}
