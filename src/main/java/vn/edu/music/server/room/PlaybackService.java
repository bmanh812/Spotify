package vn.edu.music.server.room;

import vn.edu.music.common.model.RoomState;

/** TODO TV2: host-only controls, authoritative timeline, roomVersion and streamEpoch rules. */
public interface PlaybackService {
    RoomState play(String roomId, String actorId, String requestId, long expectedRoomVersion);

    RoomState pause(String roomId, String actorId, String requestId, long expectedRoomVersion);

    RoomState seek(String roomId, String actorId, String requestId, long expectedRoomVersion, long positionMs);

    RoomState next(String roomId, String actorId, String requestId, long expectedRoomVersion);
}
