package vn.edu.music.server.room;

/** TODO TV2: permissions, request idempotency, queue limits and authoritative version updates. */
public interface QueueService {
    RoomSnapshot add(String roomId, String clientId, String trackId,
                     String requestId, long expectedRoomVersion);
}
