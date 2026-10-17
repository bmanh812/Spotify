package vn.edu.music.server.room;

/** TODO TV2: host-only controls, authoritative timeline, roomVersion and streamEpoch rules. */
public interface PlaybackService {
    RoomSnapshot play(String roomId, String actorId, String requestId, long expectedRoomVersion);

    RoomSnapshot pause(String roomId, String actorId, String requestId, long expectedRoomVersion);

    RoomSnapshot seek(String roomId, String actorId, String requestId, long expectedRoomVersion, long positionMs);

    RoomSnapshot next(String roomId, String actorId, String requestId, long expectedRoomVersion);
}
