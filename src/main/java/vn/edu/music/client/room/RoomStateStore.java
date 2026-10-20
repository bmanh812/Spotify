package vn.edu.music.client.room;

import vn.edu.music.common.model.*;
import vn.edu.music.common.protocol.*;

import java.util.List;
import java.util.Objects;

/** Sole full-state application pipeline. No playback policy or Server business logic. */
public final class RoomStateStore {
    public record View(String clientId, RoomState state, List<TrackMetadata> tracks,
                       boolean lifecyclePending, boolean uncertain) {
        public View { tracks = List.copyOf(tracks); }
        public boolean isHost() { return state != null && state.hostClientId().equals(clientId); }
    }

    private String clientId;
    private RoomState state;
    private List<TrackMetadata> tracks = List.of();
    private ProtocolMessage lifecycle;
    private boolean uncertain;

    public synchronized View view() { return new View(clientId, state, tracks, lifecycle != null, uncertain); }

    public synchronized void beginLifecycle(ProtocolMessage request) {
        if (uncertain || lifecycle != null) throw new IllegalStateException("Membership operation is pending or UNKNOWN");
        boolean leave = request.type() == MessageType.LEAVE_ROOM;
        if (leave ? state == null : state != null) throw new IllegalStateException("Invalid membership transition");
        lifecycle = request;
    }

    public synchronized void failed(ProtocolMessage request, boolean unknown) {
        if (lifecycle != null && lifecycle.requestId().equals(request.requestId())) lifecycle = null;
        if (unknown && request.type().isStateChangingRequest()) uncertain = true;
    }

    public synchronized void disconnected() {
        state = null; clientId = null; tracks = List.of(); lifecycle = null; uncertain = false;
    }

    public synchronized boolean accept(ProtocolMessage message, ProtocolMessage request) throws ProtocolException {
        var payload = ProtocolValidator.validateServerMessage(message);
        if (payload instanceof Payloads.MembershipState p) {
            boolean establish = request != null && (request.type() == MessageType.CREATE_ROOM || request.type() == MessageType.JOIN_ROOM);
            if (establish) {
                if (lifecycle == null || !lifecycle.requestId().equals(request.requestId())) return false;
                if (state != null) throw new ProtocolException("Cannot replace active membership");
                clientId = p.clientId(); state = p.state(); tracks = p.tracks(); lifecycle = null;
                return true;
            }
            if (state == null || !p.state().roomId().equals(state.roomId())) return false;
            if (!Objects.equals(p.clientId(), clientId)) throw new ProtocolException("Recipient identity mismatch");
            if (!tracks.equals(p.tracks())) throw new ProtocolException("Immutable catalog changed in a session");
            return apply(p.state());
        }
        if (payload instanceof Payloads.StateUpdate p) return apply(p.state());
        if (payload instanceof Payloads.LeaveAck p) {
            if (request == null || lifecycle == null || !lifecycle.requestId().equals(request.requestId())
                    || state == null || !state.roomId().equals(message.roomId())) return false;
            if (!Objects.equals(clientId, p.clientId())) throw new ProtocolException("LEAVE_ACK identity mismatch");
            state = null; tracks = List.of(); lifecycle = null;
            return true;
        }
        if (payload instanceof Payloads.RoomClosed p && state != null && state.roomId().equals(message.roomId())) {
            if (p.roomVersion() < state.roomVersion()) return false;
            state = null; tracks = List.of(); lifecycle = null; uncertain = false;
            return true;
        }
        return false;
    }

    private boolean apply(RoomState next) throws ProtocolException {
        if (state == null || !next.roomId().equals(state.roomId())) return false;
        ProtocolValidator.validateStateAgainstCatalog(next, tracks);
        if (next.participants().stream().noneMatch(p -> p.clientId().equals(clientId))) {
            throw new ProtocolException("Snapshot omitted the active recipient");
        }
        if (next.roomVersion() <= state.roomVersion()) return false;
        if (next.streamEpoch() < state.streamEpoch()) throw new ProtocolException("streamEpoch rolled back");
        state = next;
        return true;
    }
}
