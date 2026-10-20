package vn.edu.music.client.room;

import org.junit.jupiter.api.Test;
import vn.edu.music.common.model.*;
import vn.edu.music.common.protocol.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.music.client.ClientFixtures.*;

class RoomStateStoreTest {
    private static ProtocolMessage create() throws ProtocolException {
        return PayloadCodec.message(MessageType.CREATE_ROOM, id(), null, new Payloads.CreateRoom("Host"));
    }
    private static RoomStateStore joined() throws ProtocolException {
        RoomStateStore store = new RoomStateStore(); var request = create(); store.beginLifecycle(request);
        assertTrue(store.accept(membership(MessageType.ROOM_CREATED, request.requestId(), state(1, 0, null)), request));
        return store;
    }

    @Test void onlyMatchingLifecycleCanEstablishMembership() throws Exception {
        var store = new RoomStateStore(); var request = create();
        assertFalse(store.accept(membership(MessageType.ROOM_CREATED, request.requestId(), state(1, 0, null)), request));
        store.beginLifecycle(request);
        assertFalse(store.accept(membership(MessageType.ROOM_CREATED, id(), state(1, 0, null)), create()));
        assertNull(store.view().state());
        assertTrue(store.accept(membership(MessageType.ROOM_CREATED, request.requestId(), state(1, 0, null)), request));
        assertTrue(store.view().isHost()); assertFalse(store.view().lifecyclePending());
    }

    @Test void olderOrEqualSnapshotDoesNotOverwriteCurrentState() throws Exception {
        var store = joined();
        assertTrue(store.accept(update(state(3, 1, "t1")), null));
        assertFalse(store.accept(update(state(2, 0, null)), null));
        assertFalse(store.accept(update(state(3, 0, null)), null));
        assertEquals("t1", store.view().state().currentTrackId());
        assertEquals(3, store.view().state().roomVersion());
    }

    @Test void syncEqualVersionRemainsObservableWithoutOverwritingState() throws Exception {
        var store = joined(); var state = store.view().state();
        var sync = PayloadCodec.message(MessageType.SYNC_STATE, id(), "r1", new Payloads.StateUpdate(state, TIME));
        assertFalse(store.accept(sync, null)); assertSame(state, store.view().state());
    }

    @Test void roomClosedAndLeavePreventLateSnapshotRevival() throws Exception {
        var store = joined();
        var closed = PayloadCodec.message(MessageType.ROOM_CLOSED, id(), "r1", new Payloads.RoomClosed(2, Payloads.CloseReason.HOST_LEFT, TIME));
        assertTrue(store.accept(closed, null));
        assertFalse(store.accept(update(state(3, 1, "t1")), null)); assertNull(store.view().state());
        store = joined();
        var leave = PayloadCodec.message(MessageType.LEAVE_ROOM, id(), "r1", new Payloads.LeaveRoom());
        store.beginLifecycle(leave);
        var ack = PayloadCodec.message(MessageType.LEAVE_ACK, leave.requestId(), "r1", new Payloads.LeaveAck("c1", 2, true));
        assertFalse(store.accept(ack, null));
        assertTrue(store.accept(ack, leave));
        assertFalse(store.accept(update(state(4, 1, "t1")), null)); assertNull(store.view().state());
    }

    @Test void staleLifecycleResponseCannotRebindAfterRejoin() throws Exception {
        var store = new RoomStateStore(); var old = create(); store.beginLifecycle(old);
        store.failed(old, false);
        var current = create(); store.beginLifecycle(current);
        assertFalse(store.accept(membership(MessageType.ROOM_CREATED, old.requestId(), state(1, 0, null)), old));
        assertTrue(store.accept(membership(MessageType.ROOM_CREATED, current.requestId(), state(1, 0, null)), current));
    }

    @Test void unknownLifecycleBlocksNewCommandsUntilDisconnect() throws Exception {
        var store = new RoomStateStore(); var request = create(); store.beginLifecycle(request);
        store.failed(request, true);
        assertTrue(store.view().uncertain());
        assertThrows(IllegalStateException.class, () -> store.beginLifecycle(create()));
        store.disconnected(); assertFalse(store.view().uncertain()); store.beginLifecycle(create());
    }

    @Test void rejectsUnknownCatalogTrackAndEpochRollback() throws Exception {
        var store = joined();
        assertThrows(ProtocolException.class, () -> store.accept(update(state(2, 1, "missing")), null));
        store.accept(update(state(2, 2, "t1")), null);
        assertThrows(ProtocolException.class, () -> store.accept(update(state(3, 1, "t1")), null));
    }

    @Test void participantOrQueueVersionDoesNotImplyAudioEpochChange() throws Exception {
        var store = joined(); store.accept(update(state(2, 1, "t1")), null);
        var next = new RoomState("r1", "ABC123", "c1", List.of(new Participant("c1", "Host"), new Participant("c2", "Member")),
                "t1", PlaybackState.PAUSED, 0, TIME, 3, 1, List.of(new QueueItem("q1", "t1", "c2")));
        assertTrue(store.accept(update(next), null));
        assertEquals(1, store.view().state().streamEpoch()); assertEquals(2, store.view().state().participants().size());
    }
}
