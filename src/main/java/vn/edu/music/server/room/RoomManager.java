package vn.edu.music.server.room;

import vn.edu.music.common.model.RoomState;

/** TODO TV2: implement create/join/leave, capacity, host departure and per-room isolation. */
public interface RoomManager {
    Membership createRoom(String nickname);

    Membership joinRoom(String roomCode, String nickname);

    RoomState snapshotForMember(String roomId, String clientId);

    void leaveRoom(String roomId, String clientId);

    record Membership(String clientId, RoomState room) {
    }
}
