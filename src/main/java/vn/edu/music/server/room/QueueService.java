package vn.edu.music.server.room;

import vn.edu.music.common.model.RoomState;

/** TODO TV2: permissions, request idempotency, queue limits and authoritative version updates. */
public interface QueueService {
    RoomState add(String roomId, String clientId, String trackId,
                     String requestId, long expectedRoomVersion);
}
