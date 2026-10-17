package vn.edu.music.server.network;

import vn.edu.music.common.protocol.ErrorCode;
import vn.edu.music.common.protocol.ProtocolMessage;

/** Honest scaffold behavior: no room, queue, playback, PING or streaming handler is implemented yet. */
public final class BootstrapDispatcher implements MessageDispatcher {
    @Override
    public ProtocolMessage dispatch(ProtocolMessage request) {
        if (request.protocolVersion() != ProtocolMessage.DRAFT_VERSION) {
            return ProtocolMessage.error(request, ErrorCode.UNSUPPORTED_VERSION, "Unsupported protocol version");
        }
        if (!request.type().isClientRequest()) {
            return ProtocolMessage.error(request, ErrorCode.INVALID_REQUEST, "Expected a Client request");
        }
        return ProtocolMessage.error(request, ErrorCode.NOT_IMPLEMENTED,
                request.type() + " is not implemented in the project skeleton");
    }
}
